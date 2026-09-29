package ac.thorium.mc.plugin.transport;

public final class AuthException extends Exception {
    public final int status;
    public final boolean retryable;

    public final long retryAfterMs;

    public AuthException(int status, String message, Throwable cause) {
        this(status, message, cause, -1L);
    }

    public AuthException(int status, String message, Throwable cause, long retryAfterMs) {
        super(message, cause);
        this.status = status;
        this.retryAfterMs = retryAfterMs;
        this.retryable = status < 0 || status >= 500 || status == 429;
    }
}
