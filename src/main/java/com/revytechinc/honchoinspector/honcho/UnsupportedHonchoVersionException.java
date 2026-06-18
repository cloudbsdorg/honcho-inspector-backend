package com.revytechinc.honchoinspector.honcho;

/**
 * Thrown when the {@link HonchoClientFactory} is asked to resolve a Honcho API
 * version that no registered {@link HonchoClient} implementation claims to
 * serve.
 *
 * <p>This is a configuration-time / startup-shape error rather than a runtime
 * network error: it indicates the build is missing the client implementation
 * for the requested version. The error message includes a pointer to
 * {@code docs/honcho-providers.md} (added in T30) so operators and engineers
 * have a one-stop remediation guide.
 */
public class UnsupportedHonchoVersionException extends RuntimeException {

    public UnsupportedHonchoVersionException(String message) {
        super(message);
    }
}
