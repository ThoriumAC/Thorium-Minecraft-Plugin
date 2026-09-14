package ac.thorium.mc.plugin.transport;

public final class AuthException extends Exception {
    public final int status;
    public final boolean retryable;

    public AuthException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.retryable = status < 0 || status >= 500;
    }
}
