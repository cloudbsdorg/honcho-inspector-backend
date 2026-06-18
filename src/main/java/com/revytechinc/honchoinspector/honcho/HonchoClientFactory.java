package com.revytechinc.honchoinspector.honcho;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Indexes all {@link HonchoClient} beans Spring discovers at boot and dispatches
 * callers to the right implementation based on the requested
 * {@link HonchoApiVersion}.
 *
 * <p>The dispatch table is built eagerly in the constructor: if any registered
 * client claims a version it does not actually implement, or if two clients
 * collide on a version, the application fails fast at boot rather than
 * silently misrouting requests at runtime. This is intentional — the factory
 * is configuration glue, and configuration errors should surface as startup
 * failures, not as 500s in production traffic.
 *
 * <p>The {@link #resolveVersion(String, HonchoApiVersion)} helper centralizes
 * the override-or-fallback policy used by callers that can take a per-request
 * or per-profile version override (e.g. {@code X-Honcho-Api-Version} header,
 * {@code Profile.apiVersion} column) but still need a default to fall back to.
 */
@Component
public class HonchoClientFactory {

    private final Map<HonchoApiVersion, HonchoClient> clientsByVersion;

    public HonchoClientFactory(List<HonchoClient> clients) {
        Map<HonchoApiVersion, HonchoClient> map = new HashMap<>();
        for (HonchoClient client : clients) {
            Set<HonchoApiVersion> claimed = client.supportedVersions();
            if (claimed == null || claimed.isEmpty()) {
                throw new IllegalStateException(
                    "HonchoClient " + client.getClass().getName()
                        + " returned an empty supportedVersions() set; an implementation must claim at least one version");
            }
            for (HonchoApiVersion version : claimed) {
                HonchoClient existing = map.put(version, client);
                if (existing != null) {
                    throw new IllegalStateException(
                        "Honcho API version " + version + " is claimed by both "
                            + existing.getClass().getName() + " and "
                            + client.getClass().getName()
                            + "; each version must be served by exactly one HonchoClient");
                }
            }
        }
        this.clientsByVersion = Collections.unmodifiableMap(map);
    }

    /**
     * Look up the {@link HonchoClient} implementation registered for
     * {@code version}.
     *
     * @throws UnsupportedHonchoVersionException if no registered client claims
     *         {@code version}. The message lists every supported version and
     *         points the operator at {@code docs/honcho-providers.md} for
     *         instructions on adding a new implementation.
     */
    public HonchoClient clientFor(HonchoApiVersion version) {
        HonchoClient client = clientsByVersion.get(version);
        if (client == null) {
            throw new UnsupportedHonchoVersionException(
                "Honcho version " + version + " is not supported by this build. Supported versions: "
                    + supportedVersionsList() + ". See docs/honcho-providers.md for how to add support.");
        }
        return client;
    }

    /**
     * Resolve an override string to a {@link HonchoApiVersion}, falling back
     * to {@code fallback} when the override is null or blank.
     *
     * <p>Static so callers that don't have a {@code HonchoClientFactory}
     * instance handy (e.g. property binders, simple controllers) can still
     * apply the same policy without needing constructor injection of the
     * factory itself.
     *
     * @throws IllegalArgumentException if {@code overrideOrNull} is non-blank
     *         but not a recognized version. Propagated from
     *         {@link HonchoApiVersion#fromString(String)}; the message lists
     *         every valid version.
     */
    public static HonchoApiVersion resolveVersion(String overrideOrNull, HonchoApiVersion fallback) {
        if (overrideOrNull == null || overrideOrNull.isBlank()) {
            return fallback;
        }
        return HonchoApiVersion.fromString(overrideOrNull);
    }

    /**
     * Sorted, comma-separated list of versions actually registered in this
     * factory. Used to build helpful error messages.
     */
    private String supportedVersionsList() {
        if (clientsByVersion.isEmpty()) {
            return "[]";
        }
        Set<HonchoApiVersion> sorted = new TreeSet<>(clientsByVersion.keySet());
        return sorted.toString();
    }
}
