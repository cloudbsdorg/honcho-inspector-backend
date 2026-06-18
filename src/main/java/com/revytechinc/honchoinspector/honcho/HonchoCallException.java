package com.revytechinc.honchoinspector.honcho;

/**
 * Thrown when a call to the upstream Honcho service fails — either because
 * Honcho returned a non-2xx status, or because the proxy could not reach
 * Honcho at all (network error, DNS, timeout, TLS).
 *
 * <p>Carries the HTTP status Honcho returned (or {@code 502} for transport
 * failures) and a truncated response body so callers can render a useful
 * error to the inspector UI without logging megabytes of upstream payload.
 *
 * <p>Originally a nested class on {@code HonchoProxyService}; promoted to
 * its own top-level type in T6 so {@link HonchoProvider} implementations
 * (and future registry code) can throw it without depending on the proxy
 * service that the provider model is in the process of replacing.
 */
public class HonchoCallException extends RuntimeException {

    private final int status;
    private final String body;

    public HonchoCallException(String message, int status, String body) {
        super(message);
        this.status = status;
        this.body = body;
    }

    /** HTTP status returned by Honcho, or {@code 502} for transport failures. */
    public int status() {
        return status;
    }

    /**
     * Truncated response body from Honcho. May be {@code null} or empty when
     * the failure occurred before a body was received (e.g. connection refused).
     */
    public String body() {
        return body;
    }
}
