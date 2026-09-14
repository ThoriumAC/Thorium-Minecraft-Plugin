package ac.thorium.mc.plugin.transport;

import ac.thorium.mc.proto.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class EngineConnectionTest {
    static final class FakeEngine extends WebSocketServer {
        final BlockingQueue<WebSocket> opened = new LinkedBlockingQueue<>();
        final BlockingQueue<UpStream> frames = new LinkedBlockingQueue<>();
        final List<ClientHandshake> handshakes = new CopyOnWriteArrayList<>();
        FakeEngine() { super(new InetSocketAddress("127.0.0.1", 0)); setReuseAddr(true); }
        @Override public void onOpen(WebSocket c, ClientHandshake h) { handshakes.add(h); opened.add(c); }
        @Override public void onClose(WebSocket c, int code, String reason, boolean remote) {}
        @Override public void onMessage(WebSocket c, String m) {}
        @Override public void onMessage(WebSocket c, ByteBuffer m) {
            try { byte[] b = new byte[m.remaining()]; m.get(b); frames.add(UpStream.parseFrom(b)); } catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public void onError(WebSocket c, Exception e) {}
        @Override public void onStart() {}
        static byte[] ack(boolean accepted, int flushMs) {
            return DownStream.newBuilder().setHelloAck(HelloAck.newBuilder().setAccepted(accepted).setPolicy(IngestPolicy.newBuilder().setFlushIntervalMs(flushMs))).build().toByteArray();
        }
    }

    static final class Recorder implements DownstreamHandler {
        final BlockingQueue<Verdict> verdicts = new LinkedBlockingQueue<>();
        final BlockingQueue<IngestPolicy> policies = new LinkedBlockingQueue<>();
        final BlockingQueue<int[]> gateway = new LinkedBlockingQueue<>();
        final BlockingQueue<ConnectionState> states = new LinkedBlockingQueue<>();
        final AtomicInteger disconnects = new AtomicInteger();
        public void onHelloAck(HelloAck ack) {}
        public void onVerdict(Verdict v) { verdicts.add(v); }
        public void onPolicy(IngestPolicy p) { policies.add(p); }
        public void onPluginUpdate(PluginUpdate u) {}
        public void onGatewayControl(int op, byte[] payload) { gateway.add(new int[]{op, payload.length}); }
        public void onStateChange(ConnectionState from, ConnectionState to) { states.add(to); }
        public void onDisconnected() { disconnects.incrementAndGet(); }
        void await(ConnectionState want) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) { if (states.poll(200, TimeUnit.MILLISECONDS) == want) return; }
            fail("never reached " + want);
        }
    }

    FakeEngine engine; EngineConnection conn; Recorder rec;
    final AtomicInteger authCalls = new AtomicInteger(), helloCalls = new AtomicInteger();

    @BeforeEach void up() throws Exception { engine = new FakeEngine(); engine.start(); Thread.sleep(200); rec = new Recorder(); }
    @AfterEach void down() throws Exception { if (conn != null) conn.stop(); engine.stop(500); }

    private void connect(String devToken) {
        ConnectionConfig cfg = new ConnectionConfig("http://127.0.0.1:" + engine.getPort(), devToken, "0.1.0").timings(50, 200, 1000, 1000, 400);
        TokenSource tokens = () -> "sess-" + authCalls.incrementAndGet();
        HelloSupplier hello = () -> Hello.newBuilder().setProtocol(1).setMcVersion("1.21.4").setPluginVersion("h" + helloCalls.incrementAndGet()).build();
        conn = new EngineConnection(cfg, tokens, hello, rec, Logger.getLogger("test"));
        conn.start();
    }

    private WebSocket ready() throws Exception {
        WebSocket ws = engine.opened.poll(3, TimeUnit.SECONDS);
        assertNotNull(ws);
        assertTrue(engine.frames.poll(3, TimeUnit.SECONDS).hasHello());
        ws.send(FakeEngine.ack(true, 100));
        rec.await(ConnectionState.READY);
        return ws;
    }

    @Test void handshakeHeadersPolicySendVerdict() throws Exception {
        connect("");
        WebSocket ws = ready();
        ClientHandshake h = engine.handshakes.get(0);
        assertEquals("sess-1", h.getFieldValue("X-SESSION-TOKEN"));
        assertEquals("0.1.0", h.getFieldValue("X-THORIUM-VERSION"));
        assertEquals("minecraft", h.getFieldValue("X-THORIUM-GAME"));
        assertEquals(100, rec.policies.poll(2, TimeUnit.SECONDS).getFlushIntervalMs());
        assertTrue(conn.send(UpStream.newBuilder().setHeartbeat(Heartbeat.newBuilder().setTps(20)).build()));
        assertTrue(engine.frames.poll(2, TimeUnit.SECONDS).hasHeartbeat());
        ws.send(DownStream.newBuilder().setVerdict(Verdict.newBuilder().setAlertType("MCSpeed")).build().toByteArray());
        assertEquals("MCSpeed", rec.verdicts.poll(2, TimeUnit.SECONDS).getAlertType());
    }

    @Test void devTokenSkipsAuth() throws Exception {
        connect("dev-token");
        assertNotNull(engine.opened.poll(3, TimeUnit.SECONDS));
        assertEquals("dev-token", engine.handshakes.get(0).getFieldValue("X-SESSION-TOKEN"));
        assertEquals(0, authCalls.get());
    }

    @Test void reconnectsAfterCloseWithFreshAuth() throws Exception {
        connect("");
        WebSocket ws = ready();
        ws.close(1013, "backend unavailable");
        rec.await(ConnectionState.BACKOFF);
        assertNotNull(engine.opened.poll(3, TimeUnit.SECONDS));
        assertEquals("sess-2", engine.handshakes.get(1).getFieldValue("X-SESSION-TOKEN"));
        assertEquals(1, rec.disconnects.get());
    }

    @Test void gatewayControlAndReconnectOpcode() throws Exception {
        connect("");
        WebSocket ws = ready();
        ws.send(new byte[]{0x04, 'h', 'i'});
        assertArrayEquals(new int[]{4, 2}, rec.gateway.poll(2, TimeUnit.SECONDS));
        ws.send(new byte[]{0x03});
        assertNotNull(engine.opened.poll(3, TimeUnit.SECONDS));
        assertEquals(2, engine.handshakes.size());
    }

    @Test void resyncTextAndControlResendHello() throws Exception {
        connect("");
        WebSocket ws = ready();
        ws.send("{\"type\":\"resync\"}");
        assertEquals("h2", engine.frames.poll(3, TimeUnit.SECONDS).getHello().getPluginVersion());
        ws.send(DownStream.newBuilder().setControl(Control.newBuilder().setResync(Resync.newBuilder())).build().toByteArray());
        assertEquals("h3", engine.frames.poll(3, TimeUnit.SECONDS).getHello().getPluginVersion());
    }

    @Test void disconnectNoReconnectStopsUntilReconnectNow() throws Exception {
        connect("");
        WebSocket ws = ready();
        ws.send(DownStream.newBuilder().setControl(Control.newBuilder().setDisconnect(Disconnect.newBuilder().setReconnect(false))).build().toByteArray());
        rec.await(ConnectionState.STOPPED);
        assertNull(engine.opened.poll(600, TimeUnit.MILLISECONDS));
        conn.reconnectNow();
        assertNotNull(engine.opened.poll(3, TimeUnit.SECONDS));
    }

    @Test void outdatedCloseHoldsThenReconnectNowBreaksHold() throws Exception {
        connect("");
        WebSocket ws = ready();
        ws.close(4422, "Your Thorium plugin is too old. Please update: https://dl.thorium.ac/x");
        rec.await(ConnectionState.HELD);
        conn.reconnectNow();
        assertNotNull(engine.opened.poll(3, TimeUnit.SECONDS));
    }
}
