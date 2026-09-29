package ac.thorium.mc.plugin.transport;

import ac.thorium.mc.proto.Control;
import ac.thorium.mc.proto.DownStream;
import ac.thorium.mc.proto.HelloAck;
import ac.thorium.mc.proto.UpStream;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.InvalidHandshakeException;
import org.java_websocket.extensions.permessage_deflate.PerMessageDeflateExtension;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class EngineConnection {
    private static final int CLOSE_OUTDATED = 4422;

    static final int MAX_QUEUED_BYTES = 8 * 1024 * 1024;
    static final int MAX_QUEUED_FRAMES = 1024;

    private final ConnectionConfig cfg;
    private final TokenSource tokens;
    private final HelloSupplier hello;
    private final DownstreamHandler handler;
    private final Logger log;
    private final Backoff backoff;

    private final Object lock = new Object();
    private volatile ConnectionState state = ConnectionState.STOPPED;
    private volatile boolean running;
    private volatile boolean wake;
    private volatile Thread thread;
    private volatile Client client;
    private final AtomicInteger reconnects = new AtomicInteger();

    private volatile long authRetryAfterMs = -1L;
    private volatile long lastStateChangeMs = System.currentTimeMillis();
    private volatile long readyAtMs;
    private volatile String lastSocketError = "";
    private volatile long lastAuthErrorLogMs;
    private volatile long lastDropLogMs;
    private final AtomicLong droppedFrames = new AtomicLong();
    private volatile String serverIdHex = "";

    private volatile CountDownLatch ackLatch = new CountDownLatch(1);
    private volatile HelloAck ack;
    private volatile CountDownLatch closeLatch = new CountDownLatch(1);
    private volatile int closeCode;
    private volatile String closeReason = "";
    private volatile long requestedBackoffMs = -1;
    private volatile boolean stopRequestedByEngine;

    public EngineConnection(ConnectionConfig cfg, TokenSource tokens, HelloSupplier hello, DownstreamHandler handler, Logger log) {
        this.cfg = cfg; this.tokens = tokens; this.hello = hello; this.handler = handler; this.log = log;
        this.backoff = new Backoff(cfg.backoffInitialMs, cfg.backoffMaxMs, new Random());
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        stopRequestedByEngine = false;
        Thread t = new Thread(this::loop, "Thorium-Net");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    private static final long STOP_JOIN_MS = 250;
    static final long STABLE_MS = 60_000;

    public void stop() {
        Thread t;
        synchronized (this) {
            if (!running) return;
            running = false;
            t = thread;
        }
        closeClient(1000, "plugin disabled");
        wakeUp();
        if (t != null) {
            try {
                t.join(STOP_JOIN_MS);
                if (t.isAlive()) { t.interrupt(); t.join(STOP_JOIN_MS); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        setState(ConnectionState.STOPPED);
    }

    public boolean send(UpStream msg) {
        Client c = client;
        if (state != ConnectionState.READY || c == null || !c.isOpen()) return false;
        if (backedUp(outQueue(c))) { onDropped(); return false; }
        try { c.send(msg.toByteArray()); return true; } catch (Throwable t) { return false; }
    }

    static boolean backedUp(java.util.Collection<ByteBuffer> outQueue) {
        if (outQueue == null) return false;
        try {
            if (outQueue.size() >= MAX_QUEUED_FRAMES) return true;
            long bytes = 0;
            for (ByteBuffer b : outQueue) {
                bytes += b.remaining();
                if (bytes >= MAX_QUEUED_BYTES) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static java.util.Collection<ByteBuffer> outQueue(Client c) {
        try {
            WebSocket ws = c.getConnection();
            return ws instanceof WebSocketImpl ? ((WebSocketImpl) ws).outQueue : null;
        } catch (Throwable t) { return null; }
    }

    private void onDropped() {
        long n = droppedFrames.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastDropLogMs > 60_000) {
            lastDropLogMs = now;
            log.warning("Thorium: the gateway is not reading; dropping frames to keep them out of the heap (" + n + " so far)");
        }
    }

    public long droppedFrames() { return droppedFrames.get(); }

    public void sendHello() {
        Client c = client;
        if (c == null || !c.isOpen()) return;
        try { c.send(UpStream.newBuilder().setHello(hello.buildHello()).build().toByteArray()); }
        catch (Throwable t) { log.log(Level.WARNING, "Thorium: failed to send Hello", t); }
    }

    public void reconnectNow() {
        stopRequestedByEngine = false;
        backoff.reset();
        if (!running) { start(); return; }
        Client c = client;
        if (c != null && c.isOpen()) closeClient(1000, "reconnect requested");
        wakeUp();
    }

    public ConnectionState state() { return state; }
    public int reconnects() { return reconnects.get(); }

    public String statusLine() {
        long ago = System.currentTimeMillis() - lastStateChangeMs;
        String sid = serverIdHex.isEmpty() ? "" : ", server " + serverIdHex.substring(0, Math.min(8, serverIdHex.length())) + "…";
        long dropped = droppedFrames.get();
        return state + sid + ", " + reconnects.get() + " reconnects, " + (ago / 1000) + "s in state"
                + (dropped == 0 ? "" : ", " + dropped + " frames dropped (send queue full)");
    }

    private void loop() {
        while (running) {
            try {
                if (stopRequestedByEngine) { setState(ConnectionState.STOPPED); waitFor(Long.MAX_VALUE); continue; }
                String token = obtainToken();
                if (token == null) { sleepBackoff(nextAuthDelayMs()); continue; }
                if (!connectAndHandshake(token)) { sleepBackoff(backoff.nextDelayMs()); continue; }
                awaitClose();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                log.log(Level.WARNING, "Thorium: network loop error", t);
                closeClient(1011, "internal error");
                try { sleepBackoff(backoff.nextDelayMs()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        closeClient(1000, "plugin disabled");
        setState(ConnectionState.STOPPED);
    }

    private String obtainToken() {
        setState(ConnectionState.AUTHENTICATING);
        authRetryAfterMs = -1L;
        if (!cfg.devSessionToken.isEmpty()) return cfg.devSessionToken;
        try {
            return tokens.fetchSessionToken();
        } catch (AuthException e) {
            authRetryAfterMs = e.retryAfterMs;
            long now = System.currentTimeMillis();
            if (!e.retryable && now - lastAuthErrorLogMs > 300_000) {
                lastAuthErrorLogMs = now;
                log.severe("Thorium: " + fatalAuthAdvice(e.status));
            } else if (e.status == 429) {
                log.warning("Thorium: the gateway is rate limiting session auth (HTTP 429) - the server token is fine. Backing off and retrying.");
            } else if (e.retryable) {
                log.warning("Thorium: session auth failed (" + e.getMessage() + "), retrying");
            }
            return null;
        }
    }

    private static String fatalAuthAdvice(int status) {
        if (status == 402) {
            return "the gateway refused this server on the network's plan (HTTP 402). "
                 + "The Free plan covers one server; add a plan or remove another backend, then /thorium reconnect.";
        }
        return "gateway rejected the server token (HTTP " + status + "). Check server-token in config.yml, then /thorium reconnect.";
    }

    private long nextAuthDelayMs() {
        long ours = backoff.nextDelayMs();
        return authRetryAfterMs > 0 ? Math.max(ours, authRetryAfterMs) : ours;
    }

    private boolean connectAndHandshake(String sessionToken) throws InterruptedException {
        setState(ConnectionState.CONNECTING);
        Map<String, String> headers = new HashMap<>();
        headers.put("X-SESSION-TOKEN", sessionToken);
        headers.put("X-THORIUM-VERSION", cfg.pluginVersion);
        headers.put("X-THORIUM-GAME", "minecraft");
        headers.put("User-Agent", "Thorium-Minecraft/" + cfg.pluginVersion);
        ackLatch = new CountDownLatch(1);
        closeLatch = new CountDownLatch(1);
        ack = null; closeCode = 0; closeReason = ""; requestedBackoffMs = -1; lastSocketError = ""; readyAtMs = 0;
        Client c = new Client(URI.create(cfg.webSocketUrl()), headers);
        c.setConnectionLostTimeout(30);
        client = c;
        boolean open;
        try { open = c.connectBlocking(cfg.connectTimeoutMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { throw e; }
        catch (Throwable t) { open = false; }
        if (!open || !c.isOpen()) {
            if (closeCode == CLOSE_OUTDATED) return handleClosed();
            log.warning("Thorium: could not connect to " + cfg.webSocketUrl() + " (" + closeDescription(closeCode, closeReason, -1, lastSocketError) + ")");
            closeClient(1000, "connect failed");
            return false;
        }
        setState(ConnectionState.HELLO_SENT);
        sendHello();
        if (!ackLatch.await(cfg.helloAckTimeoutMs, TimeUnit.MILLISECONDS) || ack == null) {
            if (closeLatch.getCount() == 0) return handleClosed();
            log.warning("Thorium: no HelloAck within " + cfg.helloAckTimeoutMs + " ms");
            closeClient(1000, "hello timeout");
            return false;
        }
        HelloAck a = ack;
        if (!a.getAccepted()) {
            log.severe("Thorium: engine rejected Hello: " + a.getRejectReason());
            closeClient(1000, "rejected");
            return false;
        }
        serverIdHex = hex(a.getServerId().toByteArray());
        readyAtMs = System.currentTimeMillis();
        setState(ConnectionState.READY);
        safe(() -> handler.onHelloAck(a));
        if (a.hasPolicy()) safe(() -> handler.onPolicy(a.getPolicy()));
        log.info("Thorium: connected to engine (server " + serverIdHex + ")");
        return true;
    }

    private void awaitClose() throws InterruptedException {
        closeLatch.await();
        reconnects.incrementAndGet();
        safe(handler::onDisconnected);
        handleClosed();
    }

    private boolean handleClosed() throws InterruptedException {
        int code = closeCode;
        String reason = closeReason;
        client = null;
        if (!running) return false;
        if (code == CLOSE_OUTDATED) {
            log.severe("Thorium: plugin outdated, holding reconnects for " + (cfg.outdatedHoldMs / 60000) + " min: " + reason);
            setState(ConnectionState.HELD);
            waitFor(cfg.outdatedHoldMs);
            return false;
        }
        if (stopRequestedByEngine) { log.warning("Thorium: engine asked us to disconnect: " + reason); return false; }
        if (requestedBackoffMs > 0) { sleepBackoff(requestedBackoffMs); return false; }
        long up = readyAtMs == 0 ? -1 : System.currentTimeMillis() - readyAtMs;
        // Only a connection that held counts as recovered: a gateway that accepts
        // and drops at once would otherwise be retried, and every online player
        // re-stated, every couple of seconds.
        if (up >= STABLE_MS) backoff.reset();
        log.info("Thorium: connection closed (" + closeDescription(code, reason, up, lastSocketError) + "), reconnecting");
        sleepBackoff(backoff.nextDelayMs());
        return false;
    }

    static String closeDescription(int code, String reason, long upMs, String lastError) {
        StringBuilder sb = new StringBuilder();
        sb.append(code == 0 ? "never opened" : String.valueOf(code));
        if (reason != null && !reason.isEmpty()) sb.append(' ').append(reason);
        else if (code == 1006) sb.append(" no close frame; the gateway or the network dropped the socket");
        if (upMs >= 0) sb.append(", up ").append(upMs / 1000).append("s");
        if (lastError != null && !lastError.isEmpty()) sb.append(", last socket error: ").append(lastError);
        return sb.toString();
    }

    private void sleepBackoff(long ms) throws InterruptedException {
        setState(ConnectionState.BACKOFF);
        waitFor(ms);
    }

    private void waitFor(long ms) throws InterruptedException {
        boolean forever = ms == Long.MAX_VALUE;
        long deadline = forever ? 0 : System.currentTimeMillis() + ms;
        synchronized (lock) {
            while (running && !wake) {
                long left = forever ? 60_000 : deadline - System.currentTimeMillis();
                if (!forever && left <= 0) break;
                lock.wait(Math.max(1, Math.min(left, 60_000)));
            }
            wake = false;
        }
    }

    private void wakeUp() { synchronized (lock) { wake = true; lock.notifyAll(); } }

    private void closeClient(int code, String reason) {
        Client c = client;
        if (c != null) { try { c.close(code, reason); } catch (Throwable ignored) {} }
    }

    private void setState(ConnectionState s) {
        ConnectionState old = state;
        if (old == s) return;
        state = s;
        lastStateChangeMs = System.currentTimeMillis();
        safe(() -> handler.onStateChange(old, s));
    }

    private void safe(Runnable r) { try { r.run(); } catch (Throwable t) { log.log(Level.WARNING, "Thorium: handler error", t); } }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private void onBinary(byte[] frame) {
        if (FrameDiscriminator.isGatewayControl(frame)) {
            int op = FrameDiscriminator.opcode(frame);
            byte[] payload = new byte[frame.length - 1];
            System.arraycopy(frame, 1, payload, 0, payload.length);
            safe(() -> handler.onGatewayControl(op, payload));
            if (op == FrameDiscriminator.GATEWAY_RECONNECT) closeClient(1000, "gateway reconnect");
            return;
        }
        DownStream d;
        try { d = DownStream.parseFrom(frame); } catch (Throwable t) { log.fine("Thorium: malformed DownStream frame"); return; }
        switch (d.getMsgCase()) {
            case HELLO_ACK: ack = d.getHelloAck(); ackLatch.countDown(); break;
            case VERDICT: safe(() -> handler.onVerdict(d.getVerdict())); break;
            case MITIGATE: safe(() -> handler.onMitigate(d.getMitigate())); break;
            case CONFIG: safe(() -> handler.onConfig(d.getConfig())); break;
            case CONTROL: onControl(d.getControl()); break;
            default: break;
        }
    }

    private void onControl(Control c) {
        switch (c.getKindCase()) {
            case POLICY: safe(() -> handler.onPolicy(c.getPolicy())); break;
            case UPDATE: safe(() -> handler.onPluginUpdate(c.getUpdate())); break;
            case RESYNC: sendHello(); break;
            case DISCONNECT:
                if (c.getDisconnect().getReconnect()) requestedBackoffMs = Math.max(0, c.getDisconnect().getBackoffMs());
                else stopRequestedByEngine = true;
                closeClient(1000, "engine disconnect: " + c.getDisconnect().getReason());
                break;
            default: break;
        }
    }

    private final class Client extends WebSocketClient {
        Client(URI uri, Map<String, String> headers) {
            super(uri, new Draft_6455(Collections.singletonList(new PerMessageDeflateExtension())), headers);
        }
        @Override public void onOpen(ServerHandshake h) {}
        @Override public void onMessage(String text) { if (FrameDiscriminator.isResyncText(text)) sendHello(); }
        @Override public void onMessage(ByteBuffer bytes) { byte[] b = new byte[bytes.remaining()]; bytes.get(b); onBinary(b); }
        @Override public void onClose(int code, String reason, boolean remote) {
            if (closeCode != CLOSE_OUTDATED) { closeCode = code; closeReason = reason == null ? "" : reason; }
            closeLatch.countDown();
            ackLatch.countDown();
        }
        @Override public void onError(Exception e) {
            if (e instanceof InvalidHandshakeException && e.getMessage() != null && e.getMessage().contains("422")) {
                closeCode = CLOSE_OUTDATED; closeReason = e.getMessage();
            }
            String msg = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            lastSocketError = msg;
            log.log(Level.FINE, "Thorium: websocket error", e);
        }
    }
}
