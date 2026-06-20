package com.revytechinc.honchoinspector.honcho;

import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-version dispatch table that maps each {@link HonchoOperation} known to a
 * particular {@link HonchoApiVersion} to the {@link HonchoProvider} responsible
 * for executing it.
 *
 * <p>One instance is built (eagerly) per {@link HonchoClient} implementation
 * (e.g. {@code HonchoV3Client}). The caller supplies the target version and the
 * full set of {@code HonchoProvider} beans Spring discovered; the registry
 * filters out providers that don't claim that version and registers the rest.
 *
 * <h2>Construction semantics</h2>
 * <ul>
 *   <li><b>Version filter:</b> a provider is consulted only when its
 *       {@link HonchoProvider#supportedVersions()} set contains the target
 *       version. Providers that don't claim the version are skipped silently
 *       — the registry belongs to a single version by design.</li>
 *   <li><b>Per-operation registration:</b> every operation in
 *       {@link HonchoProvider#operations()} is bound to its provider, so a
 *       single multi-operation provider contributes one entry per op.</li>
 *   <li><b>Deterministic collision handling:</b> when two providers claim the
 *       same operation, a WARN log records both class names and the
 *       earlier-registered provider wins. "Earlier" is determined by sorting
 *       the input list by {@link Class#getName()} before iterating, which
 *       makes the outcome independent of the order Spring returned the beans
 *       in. The first-registered provider is also the alphabetically-earlier
 *       class name, which is the test's most predictable form of "first
 *       wins".</li>
 *   <li><b>Provider-count semantics:</b> {@link #providerCount()} returns the
 *       number of distinct provider <em>instances</em> that contribute at
 *       anything to this registry, NOT the number of operations. This lets
 *       later coverage tests verify "8 V3 providers covering 24 ops" without
 *       confusing the two numbers.</li>
 * </ul>
 *
 * <h2>Why eager init at construction</h2>
 * Boot-time failures are far cheaper than runtime 500s. If a registry can't be
 * built (null inputs, duplicate operations that mask a real bug), the caller
 * fails fast at startup with a precise message instead of returning a wrong
 * provider for the first request.
 */
public class HonchoProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(HonchoProviderRegistry.class);

    private final HonchoApiVersion version;
    private final Map<HonchoOperation, HonchoProvider> providersByOperation;
    /** Distinct providers that contributed at least one operation. Insertion order preserved for diagnostics. */
    private final Set<HonchoProvider> distinctProviders;

    /**
     * Build a registry for {@code version} from the supplied provider beans.
     *
     * <p>Providers are sorted by fully-qualified class name before iteration so
     * the collision outcome ("first wins") is deterministic regardless of the
     * order Spring injected the beans in. The first provider in that sort to
     * claim a given operation is the one that wins any subsequent collision,
     * and a WARN is logged naming both contenders.
     *
     * @param version      the Honcho API version this registry dispatches for;
     *                     must be non-null.
     * @param allProviders every {@link HonchoProvider} bean Spring discovered;
     *                     may be empty but must be non-null. Providers that
     *                     don't claim {@code version} are filtered out.
     * @throws IllegalArgumentException if {@code version} or
     *         {@code allProviders} is {@code null}.
     */
    public HonchoProviderRegistry(HonchoApiVersion version, List<HonchoProvider> allProviders) {
        if (version == null) {
            throw new IllegalArgumentException("HonchoApiVersion must not be null");
        }
        if (allProviders == null) {
            throw new IllegalArgumentException("providers list must not be null");
        }

        this.version = version;
        Map<HonchoOperation, HonchoProvider> map = new EnumMap<>(HonchoOperation.class);
        Set<HonchoProvider> distinct = new LinkedHashSet<>();

        // Sort by class name for deterministic "first wins" behavior on collisions.
        // Spring's bean ordering depends on dependency-graph topology, which is not
        // stable across configurations. Sorting makes the outcome independent of
        // that and easy to reason about in tests (alphabetically-earlier class wins).
        List<HonchoProvider> sorted = allProviders.stream()
            .sorted(Comparator.comparing(p -> p.getClass().getName()))
            .toList();

        for (HonchoProvider provider : sorted) {
            Set<HonchoApiVersion> claimed = provider.supportedVersions();
            if (claimed == null || !claimed.contains(version)) {
                continue;
            }
            distinct.add(provider);
            Set<HonchoOperation> ops = provider.operations();
            if (ops == null) {
                continue;
            }
            for (HonchoOperation op : ops) {
                // putIfAbsent (not put): we want "first registered wins" — the
                // already-bound provider must stay in the map. Using plain put
                // would return the existing value AND overwrite the map,
                // silently letting the later-registered provider win.
                HonchoProvider existing = map.putIfAbsent(op, provider);
                if (existing != null) {
                    log.warn(
                        "HonchoProviderRegistry(v{}) operation {} claimed by both {} and {}; "
                            + "first-registered (alphabetically by class name) wins, keeping {}",
                        version, op,
                        existing.getClass().getName(),
                        provider.getClass().getName(),
                        existing.getClass().getName()
                    );
                }
            }
        }

        this.providersByOperation = Collections.unmodifiableMap(map);
        this.distinctProviders = Collections.unmodifiableSet(distinct);
    }

    /**
     * Look up the provider responsible for {@code op}.
     *
     * @throws IllegalStateException if no provider in this registry covers
     *         {@code op}. The message lists every operation that IS covered so
     *         the operator can see at a glance what is and isn't wired up.
     */
    public HonchoProvider get(HonchoOperation op) {
        HonchoProvider provider = providersByOperation.get(op);
        if (provider == null) {
            throw new IllegalStateException(
                "No HonchoProvider registered for operation " + op
                    + " in HonchoProviderRegistry(version=" + version + ")."
                    + " Covered operations: " + providersByOperation.keySet()
                    + ". Either add a provider that supports this operation for version "
                    + version + ", or fix the caller to request a covered operation."
            );
        }
        return provider;
    }

    /**
     * True if any provider in this registry handles {@code op}.
     */
    public boolean covers(HonchoOperation op) {
        return providersByOperation.containsKey(op);
    }

    /**
     * The set of operations this registry can dispatch. Useful for diagnostic
     * logging ("V3 registry covers 24/24 operations") and for tests asserting
     * coverage. Iteration order matches {@code HonchoOperation} declaration
     * order because the backing map is an {@link EnumMap}.
     */
    public Set<HonchoOperation> coveredOperations() {
        return providersByOperation.keySet();
    }

    /**
     * The number of distinct provider <em>instances</em> that contribute at
     * least one operation to this registry. Note: this is NOT the operation
     * count — a single multi-operation provider contributes one to this
     * counter but several operations to {@link #coveredOperations()}.
     *
     * <p>Useful for sanity-checking that all expected V3 providers have been
     * auto-discovered (e.g. "8 V3 providers registered").
     */
    public int providerCount() {
        return distinctProviders.size();
    }

    /**
     * The API version this registry was built for.
     */
    public HonchoApiVersion version() {
        return version;
    }

    /**
     * Verify that every {@link HonchoOperation} known to this registry's
     * API version has at least one provider. Production wiring
     * ({@code HonchoV3Client}) calls this immediately after construction
     * so a misconfigured deployment fails fast at boot rather than
     * surfacing as a 500 on the first request for an uncovered op.
     *
     * @throws IllegalStateException if any operation is uncovered.
     */
    public void validateFullCoverage() {
        Set<HonchoOperation> uncovered = EnumSet.noneOf(HonchoOperation.class);
        for (HonchoOperation op : HonchoOperation.values()) {
            if (!providersByOperation.containsKey(op)) {
                uncovered.add(op);
            }
        }
        if (!uncovered.isEmpty()) {
            throw new IllegalStateException(
                "HonchoProviderRegistry(version=" + version + ") is missing providers for operations: " + uncovered);
        }
    }
}
