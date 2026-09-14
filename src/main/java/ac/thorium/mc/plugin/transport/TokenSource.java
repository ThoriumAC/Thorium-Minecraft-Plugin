package ac.thorium.mc.plugin.transport;

public interface TokenSource {
    String fetchSessionToken() throws AuthException;
}
