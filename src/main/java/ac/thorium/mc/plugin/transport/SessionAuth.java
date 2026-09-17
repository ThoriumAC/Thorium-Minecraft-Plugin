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

/** Exchanges the long-lived server token for a 60 s single-use session token at the gateway. */
public final class SessionAuth implements TokenSource {
    private static final Pattern TOKEN = Pattern.compile("\"sessionToken\"\\s*:\\s*\"([^\"]+)\"");
    private final String base, serverToken, pluginVersion;
    private final int timeoutMs;

    public SessionAuth(String gatewayBaseUrl, String serverToken, String pluginVersion, int timeoutMs) {
        this.base = gatewayBaseUrl; this.serverToken = serverToken; this.pluginVersion = pluginVersion; this.timeoutMs = timeoutMs;
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
            conn.setRequestProperty("X-THORIUM-VERSION", pluginVersion);
            conn.setRequestProperty("X-THORIUM-GAME", "minecraft");
            conn.setRequestProperty("User-Agent", "Thorium-Minecraft/" + pluginVersion);
            int status = conn.getResponseCode();
            if (status != 200) throw new AuthException(status, "session auth failed: HTTP " + status, null);
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

    /**
     * Whether this gateway-url is one the long-lived server token may cross.
     *
     * <p>{@link #fetchSessionToken()} sends X-SERVER-TOKEN over whatever scheme
     * gateway-url names, and {@link #toWebSocketUrl} maps http to ws without a
     * word about it. A typo, or a gateway-url copied out of the local-engine
     * snippet in the docs, is enough to put a server's permanent credential on
     * the wire in cleartext. Only TLS counts; anything else — including a
     * gateway-url with no scheme at all, which nothing here would fix — does not.
     */
    public static boolean isSecure(String gatewayBaseUrl) {
        String u = gatewayBaseUrl == null ? "" : gatewayBaseUrl.trim().toLowerCase(Locale.ROOT);
        return u.startsWith("https://") || u.startsWith("wss://");
    }

    /**
     * Whether the plugin may talk to this gateway-url at all.
     *
     * <p>The one cleartext case that is not a leak is a local engine reached
     * with {@code dev.session-token}: that path never calls
     * {@link #fetchSessionToken()}, so the server token stays in the config file.
     */
    public static boolean gatewayAllowed(String gatewayBaseUrl, boolean devSessionTokenSet) {
        return isSecure(gatewayBaseUrl) || devSessionTokenSet;
    }

    /** The SEVERE line this gateway-url deserves, or null when it is https. */
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
