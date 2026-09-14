package ac.thorium.mc.plugin.transport;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SessionAuthTest {
    private static String stub(int status, String body, AtomicReference<List<String>> headersOut) throws Exception {
        ServerSocket ss = new ServerSocket(0);
        Thread t = new Thread(() -> {
            try (Socket s = ss.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                 OutputStream out = s.getOutputStream()) {
                List<String> lines = new ArrayList<>();
                for (String l = in.readLine(); l != null && !l.isEmpty(); l = in.readLine()) lines.add(l);
                headersOut.set(lines);
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(b);
                out.flush();
            } catch (Exception ignored) {
            } finally { try { ss.close(); } catch (Exception ignored) {} }
        });
        t.setDaemon(true);
        t.start();
        return "http://127.0.0.1:" + ss.getLocalPort();
    }

    @Test
    void sendsHeadersAndParsesToken() throws Exception {
        AtomicReference<List<String>> headers = new AtomicReference<>();
        String base = stub(200, "{\"sessionToken\":\"abc123\"}", headers);
        String token = new SessionAuth(base, "srv-token", "0.1.0", 2000).fetchSessionToken();
        assertEquals("abc123", token);
        List<String> h = headers.get();
        assertTrue(h.get(0).startsWith("GET /api/session/auth HTTP/1.1"), h.get(0));
        assertTrue(h.stream().anyMatch(l -> l.equalsIgnoreCase("X-SERVER-TOKEN: srv-token")));
        assertTrue(h.stream().anyMatch(l -> l.equalsIgnoreCase("X-THORIUM-VERSION: 0.1.0")));
        assertTrue(h.stream().anyMatch(l -> l.equalsIgnoreCase("X-THORIUM-GAME: minecraft")));
    }

    @Test
    void unauthorizedIsNotRetryable() throws Exception {
        String base = stub(401, "", new AtomicReference<>());
        AuthException e = assertThrows(AuthException.class, () -> new SessionAuth(base, "bad", "0.1.0", 2000).fetchSessionToken());
        assertEquals(401, e.status);
        assertFalse(e.retryable);
    }

    @Test
    void serverErrorIsRetryable() throws Exception {
        String base = stub(503, "", new AtomicReference<>());
        AuthException e = assertThrows(AuthException.class, () -> new SessionAuth(base, "x", "0.1.0", 2000).fetchSessionToken());
        assertEquals(503, e.status);
        assertTrue(e.retryable);
    }

    @Test
    void connectionRefusedIsRetryable() {
        AuthException e = assertThrows(AuthException.class, () -> new SessionAuth("http://127.0.0.1:1", "x", "0.1.0", 500).fetchSessionToken());
        assertEquals(-1, e.status);
        assertTrue(e.retryable);
    }

    @Test
    void extractAndUrls() {
        assertEquals("t", SessionAuth.extractSessionToken("{ \"sessionToken\" : \"t\" }"));
        assertNull(SessionAuth.extractSessionToken("{}"));
        assertEquals("wss://gateway.thorium.ac/api/anticheat/ws", SessionAuth.toWebSocketUrl("https://gateway.thorium.ac"));
        assertEquals("ws://localhost:3100/api/anticheat/ws", SessionAuth.toWebSocketUrl("http://localhost:3100"));
        assertEquals("wss://x/api/anticheat/ws", SessionAuth.toWebSocketUrl("wss://x"));
    }
}
