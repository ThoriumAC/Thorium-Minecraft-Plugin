package ac.thorium.mc.plugin.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SessionAuth implements TokenSource {
    private static final Pattern TOKEN = Pattern.compile("\"sessionToken\"\\s*:\\s*\"([^\"]+)\"");
    private final String base, serverToken, serverName, pluginVersion;
    private final int timeoutMs;

    public SessionAuth(String gatewayBaseUrl, String serverToken, String pluginVersion, int timeoutMs) {
        this(gatewayBaseUrl, serverToken, "", pluginVersion, timeoutMs);
    }

    public SessionAuth(String gatewayBaseUrl, String serverToken, String serverName, String pluginVersion, int timeoutMs) {
        this.base = gatewayBaseUrl; this.serverToken = serverToken; this.serverName = serverName;
        this.pluginVersion = pluginVersion; this.timeoutMs = timeoutMs;
    }

    @Override
    public String fetchSessionToken() throws AuthException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/api/session/auth").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setUseCaches(false);
            conn.setRequestProperty("X-SERVER-TOKEN", serverToken);
            if (!serverName.isEmpty()) conn.setRequestProperty("X-THORIUM-SERVER", serverName);
            conn.setRequestProperty("X-THORIUM-VERSION", pluginVersion);
            conn.setRequestProperty("X-THORIUM-GAME", "minecraft");
            conn.setRequestProperty("User-Agent", "Thorium-Minecraft/" + pluginVersion);
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new AuthException(status, "session auth failed: HTTP " + status, null, retryAfterMs(conn));
            }
            String token = extractSessionToken(readAll(conn.getInputStream()));
            if (token == null) throw new AuthException(200, "session auth: no sessionToken in response", null);
            return token;
        } catch (AuthException e) {
            throw e;
        } catch (IOException e) {
            throw new AuthException(-1, "session auth: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static long retryAfterMs(HttpURLConnection conn) {
        String header = conn.getHeaderField("Retry-After");
        if (header == null) return -1L;
        try {
            long seconds = Long.parseLong(header.trim());
            if (seconds < 0) return -1L;
            return Math.min(seconds, 300L) * 1000L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        byte[] buf = new byte[4096];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int n; (n = in.read(buf)) > 0; ) { out.write(buf, 0, n); if (out.size() > 65536) break; }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    public static String extractSessionToken(String json) {
        if (json == null) return null;
        Matcher m = TOKEN.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    public static boolean isSecure(String gatewayBaseUrl) {
        String u = gatewayBaseUrl == null ? "" : gatewayBaseUrl.trim().toLowerCase(Locale.ROOT);
        return u.startsWith("https://") || u.startsWith("wss://");
    }

    public static boolean gatewayAllowed(String gatewayBaseUrl, boolean devSessionTokenSet) {
        return isSecure(gatewayBaseUrl) || devSessionTokenSet;
    }

    public static String insecureGatewayMessage(String gatewayBaseUrl, boolean devSessionTokenSet) {
        if (isSecure(gatewayBaseUrl)) return null;
        String u = gatewayBaseUrl == null ? "" : gatewayBaseUrl.trim();
        if (devSessionTokenSet) {
            return "Thorium: gateway-url \"" + u + "\" is not https. Connecting anyway because dev.session-token is set, "
                    + "so the server token stays out of it - but this is a development path and belongs on no live server.";
        }
        return "Thorium: gateway-url \"" + u + "\" is not https - the server token would cross the network in cleartext, "
                + "so the plugin is idle. Set gateway-url to an https:// address (or, for a local engine, set dev.session-token), "
                + "then /thorium reconnect.";
    }

    public static String toWebSocketUrl(String gatewayBaseUrl) {
        String u = gatewayBaseUrl;
        if (u.startsWith("https://")) u = "wss://" + u.substring(8);
        else if (u.startsWith("http://")) u = "ws://" + u.substring(7);
        return u + "/api/anticheat/ws";
    }
}
