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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SessionAuthTest {
    private static String stub(int status, String body, AtomicReference<List<String>> headersOut) throws Exception {
        return stub(status, body, headersOut, null);
    }

    private static String stub(int status, String body, AtomicReference<List<String>> headersOut, String extraHeader) throws Exception {
        ServerSocket ss = new ServerSocket(0);
        Thread t = new Thread(() -> {
            try (Socket s = ss.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                 OutputStream out = s.getOutputStream()) {
                List<String> lines = new ArrayList<>();
                for (String l = in.readLine(); l != null && !l.isEmpty(); l = in.readLine()) lines.add(l);
                headersOut.set(lines);
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                String extra = extraHeader == null ? "" : extraHeader + "\r\n";
                out.write(("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\n" + extra + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
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

    // Network tokens are shared, so the server name header identifies the backend.
    @Test
    void sendsTheDeclaredServerName() throws Exception {
        AtomicReference<List<String>> headers = new AtomicReference<>();
        String base = stub(200, "{\"sessionToken\":\"abc\"}", headers);
        new SessionAuth(base, "org_token", "bedwars", "0.1.0", 2000).fetchSessionToken();
        assertTrue(headers.get().stream().anyMatch(l -> l.equalsIgnoreCase("X-THORIUM-SERVER: bedwars")));
    }

    @Test
    void omitsTheServerNameWhenThereIsNone() throws Exception {
        // Per-server tokens carry their own identity: no name header.
        AtomicReference<List<String>> headers = new AtomicReference<>();
        String base = stub(200, "{\"sessionToken\":\"abc\"}", headers);
        new SessionAuth(base, "srv_token", "0.1.0", 2000).fetchSessionToken();
        assertFalse(headers.get().stream().anyMatch(l -> l.toUpperCase(Locale.ROOT).startsWith("X-THORIUM-SERVER")));
    }

    @Test
    void unauthorizedIsNotRetryable() throws Exception {
        String base = stub(401, "", new AtomicReference<>());
        AuthException e = assertThrows(AuthException.class, () -> new SessionAuth(base, "bad", "0.1.0", 2000).fetchSessionToken());
        assertEquals(401, e.status);
        assertFalse(e.retryable);
    }

    // 429 is transient, not a bad token.
    @Test
    void rateLimitIsRetryable() throws Exception {
        String base = stub(429, "", new AtomicReference<>());
        AuthException e = assertThrows(AuthException.class, () -> new SessionAuth(base, "x", "0.1.0", 2000).fetchSessionToken());
        assertEquals(429, e.status);
        assertTrue(e.retryable);
    }

    @Test
    void retryAfterIsReadWhenTheGatewaySendsOne() throws Exception {
        String base = stub(429, "", new AtomicReference<>(), "Retry-After: 60");
        AuthException e = assertThrows(AuthException.class, () -> new SessionAuth(base, "x", "0.1.0", 2000).fetchSessionToken());
        assertEquals(60_000L, e.retryAfterMs);
    }

    @Test
    void retryAfterIsClampedAndIgnoredWhenUnreadable() throws Exception {
        // HTTP-date Retry-After isn't parsed; the default backoff applies.
        String httpDate = stub(429, "", new AtomicReference<>(), "Retry-After: Wed, 21 Oct 2026 07:28:00 GMT");
        AuthException e1 = assertThrows(AuthException.class, () -> new SessionAuth(httpDate, "x", "0.1.0", 2000).fetchSessionToken());
        assertEquals(-1L, e1.retryAfterMs);

        // Retry-After is capped.
        String huge = stub(429, "", new AtomicReference<>(), "Retry-After: 86400");
        AuthException e2 = assertThrows(AuthException.class, () -> new SessionAuth(huge, "x", "0.1.0", 2000).fetchSessionToken());
        assertEquals(300_000L, e2.retryAfterMs);
    }

    @Test
    void paymentRequiredIsNotRetryable() throws Exception {
        // Over the plan's server allowance: retrying won't help.
        String base = stub(402, "{\"error\":\"server_limit_reached\"}", new AtomicReference<>());
        AuthException e = assertThrows(AuthException.class, () -> new SessionAuth(base, "x", "0.1.0", 2000).fetchSessionToken());
        assertEquals(402, e.status);
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
    void onlyTlsCarriesTheServerToken() {
        assertTrue(SessionAuth.isSecure("https://gateway.thorium.ac"));
        assertTrue(SessionAuth.isSecure("  HTTPS://gateway.thorium.ac  "));
        assertTrue(SessionAuth.isSecure("wss://gateway.thorium.ac"));
        assertFalse(SessionAuth.isSecure("http://gateway.thorium.ac"));
        assertFalse(SessionAuth.isSecure("ws://localhost:3100"));
        // No scheme is rejected too.
        assertFalse(SessionAuth.isSecure("gateway.thorium.ac"));
        assertFalse(SessionAuth.isSecure(""));
        assertFalse(SessionAuth.isSecure(null));
    }

    @Test
    void cleartextGatewayIsRefusedUnlessTheDevTokenIsSet() {
        assertTrue(SessionAuth.gatewayAllowed("https://gateway.thorium.ac", false));
        assertFalse(SessionAuth.gatewayAllowed("http://gateway.thorium.ac", false));
        // The local-engine path never calls fetchSessionToken, so nothing leaks.
        assertTrue(SessionAuth.gatewayAllowed("http://localhost:3100", true));

        assertNull(SessionAuth.insecureGatewayMessage("https://gateway.thorium.ac", false));
        String refused = SessionAuth.insecureGatewayMessage("http://gateway.thorium.ac", false);
        assertNotNull(refused);
        assertTrue(refused.contains("cleartext"), refused);
        assertTrue(refused.contains("http://gateway.thorium.ac"), refused);
        // The dev path logs a warning.
        String dev = SessionAuth.insecureGatewayMessage("http://localhost:3100", true);
        assertNotNull(dev);
        assertTrue(dev.contains("dev.session-token"), dev);
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
