package com.revytechinc.honchoinspector.honcho;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.revytechinc.honchoinspector.model.HonchoContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@link HonchoProviderRegistry}. Verifies the five
 * properties the dispatch layer relies on:
 *
 * <ol>
 *   <li><b>Version filter</b> — providers whose {@code supportedVersions()}
 *       don't include the target version are silently dropped.</li>
 *   <li><b>Deterministic collision</b> — when two providers claim the same
 *       operation, the alphabetically-earlier class wins and a WARN names
 *       both.</li>
 *   <li><b>Helpful missing-operation error</b> — {@code get()} for an
 *       uncovered operation throws {@link IllegalStateException} with a
 *       message that lists what's actually covered.</li>
 *   <li><b>Coverage predicate</b> — {@code covers()} agrees with the
 *       registry's internal state.</li>
 *   <li><b>Diagnostic set</b> — {@code coveredOperations()} returns every
 *       operation the registry can dispatch.</li>
 * </ol>
 *
 * <p>Most fixtures are anonymous {@link HonchoProvider}s produced by the
 * {@code provider()} helper. The collision test uses two NAMED static
 * inner classes ({@link AlphaListPeersProvider}, {@link BetaListPeersProvider})
 * because anonymous inner classes can receive the same JVM-internal numerical
 * suffix when declared in the same enclosing scope, which would break the
 * deterministic "alphabetically first wins" assertion. The registry only
 * consults {@link HonchoProvider#operations()} and
 * {@link HonchoProvider#supportedVersions()}, so the rest of the interface
 * is irrelevant here. The fixtures never get registered with Spring; the
 * registry is constructed directly via {@code new HonchoProviderRegistry(...)}.
 */
class HonchoProviderRegistryTest {

    private Logger registryLogger;
    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void attachLogCapture() {
        registryLogger = (Logger) LoggerFactory.getLogger(HonchoProviderRegistry.class);
        logCapture = new ListAppender<>();
        logCapture.start();
        registryLogger.addAppender(logCapture);
    }

    @AfterEach
    void detachLogCapture() {
        if (registryLogger != null && logCapture != null) {
            registryLogger.detachAppender(logCapture);
            logCapture.stop();
        }
    }

    /**
     * Test fixture: a {@link HonchoProvider} that advertises the given
     * operations and versions. The {@code pathTemplate} / {@code httpMethod}
     * defaults are fine here because the registry never invokes them — it
     * only reads {@code operations()} and {@code supportedVersions()}, then
     * stores the provider for later dispatch.
     */
    private static HonchoProvider provider(Set<HonchoOperation> ops, Set<HonchoApiVersion> versions) {
        return new HonchoProvider() {
            @Override
            public Set<HonchoOperation> operations() {
                return ops;
            }
            @Override
            public Set<HonchoApiVersion> supportedVersions() {
                return versions;
            }
            @Override
            public Object execute(HonchoOperation op, HonchoContext ctx, HonchoClient client,
                                  Object requestBody, Map<String, String> pathVars,
                                  Map<String, ?> queryParams) {
                throw new UnsupportedOperationException("test fixture does not execute " + op);
            }
        };
    }

    /** Convenience: a V3 provider claiming {@code ops}. */
    private static HonchoProvider v3(Set<HonchoOperation> ops) {
        return provider(ops, EnumSet.of(HonchoApiVersion.V3));
    }

    /** Convenience: a V4 provider claiming {@code ops}. */
    private static HonchoProvider v4(Set<HonchoOperation> ops) {
        return provider(ops, EnumSet.of(HonchoApiVersion.V4));
    }

    /**
     * Build the 8 V3 providers matching the layout the real T10–T13
     * implementations will produce. 5 + 5 + 4 + 2 + 4 + 2 + 1 + 1 = 24 ops,
     * matching {@link HonchoOperation#values()} count.
     */
    private static List<HonchoProvider> eightV3ProvidersCoveringAll24Ops() {
        return List.of(
            v3(EnumSet.of(HonchoOperation.LIST_PEERS, HonchoOperation.CREATE_PEER,
                HonchoOperation.GET_PEER_CARD, HonchoOperation.UPDATE_PEER_CARD,
                HonchoOperation.GET_REPRESENTATION)),
            v3(EnumSet.of(HonchoOperation.PEER_CHAT, HonchoOperation.SEARCH_PEERS,
                HonchoOperation.LIST_PEER_CONCLUSIONS, HonchoOperation.LIST_PEER_SESSIONS,
                HonchoOperation.QUERY_PEER_CONCLUSIONS)),
            v3(EnumSet.of(HonchoOperation.LIST_SESSIONS, HonchoOperation.CREATE_SESSION,
                HonchoOperation.GET_SESSION, HonchoOperation.DELETE_SESSION)),
            v3(EnumSet.of(HonchoOperation.LIST_SESSION_MESSAGES, HonchoOperation.ADD_MESSAGE)),
            v3(EnumSet.of(HonchoOperation.GET_SESSION_CONTEXT, HonchoOperation.GET_SESSION_SUMMARIES,
                HonchoOperation.GET_SESSION_PEERS, HonchoOperation.SEARCH_SESSION_MESSAGES)),
            v3(EnumSet.of(HonchoOperation.GET_WORKSPACE_INFO, HonchoOperation.GET_QUEUE_STATUS)),
            v3(EnumSet.of(HonchoOperation.SEARCH_MESSAGES)),
            v3(EnumSet.of(HonchoOperation.SCHEDULE_DREAM))
        );
    }

    @Test
    void filtersByVersion() {
        // Mix 8 V3 providers (covering all 24 ops) with one V4-only provider.
        // A V3 registry must include only the V3 ones.
        List<HonchoProvider> all = new ArrayList<>(eightV3ProvidersCoveringAll24Ops());
        all.add(v4(EnumSet.of(HonchoOperation.GET_WORKSPACE_INFO)));

        HonchoProviderRegistry v3Registry = new HonchoProviderRegistry(HonchoApiVersion.V3, all);

        assertThat(v3Registry.version()).isEqualTo(HonchoApiVersion.V3);
        assertThat(v3Registry.providerCount())
            .as("V3 registry should count only V3 providers")
            .isEqualTo(8);
        assertThat(v3Registry.coveredOperations())
            .as("V3 registry should cover every HonchoOperation")
            .containsExactlyInAnyOrder(HonchoOperation.values());
    }

    @Test
    void collisionLogsAndFirstWins() {
        // Two distinct, NAMED classes both claim LIST_PEERS. Named classes
        // (not anonymous inner classes) are required for the test to be
        // deterministic: anonymous classes declared in the same enclosing
        // scope can get the same JVM-internal numerical suffix, making the
        // "first wins" tiebreak arbitrary.
        //
        // The class names AlphaListPeersProvider and BetaListPeersProvider
        // sort alphabetically in that order, so Alpha is the registry's
        // "first-registered" winner.
        HonchoProvider alpha = new AlphaListPeersProvider();
        HonchoProvider beta = new BetaListPeersProvider();

        // Sanity check on the test itself: confirm the sort actually puts
        // alpha before beta, so the collision outcome we're about to assert
        // is not a coincidence of JVM-internal class naming.
        assertThat(alpha.getClass().getName())
            .as("alpha class name must sort before beta for this test to be deterministic")
            .isLessThan(beta.getClass().getName());

        HonchoProviderRegistry registry = new HonchoProviderRegistry(
            HonchoApiVersion.V3, List.of(beta, alpha)); // intentionally out of order

        // The registry sorts internally, so even though we passed beta first,
        // the alphabetically-earlier class (alpha) wins the collision.
        assertThat(registry.get(HonchoOperation.LIST_PEERS)).isSameAs(alpha);

        // WARN log: must mention both class names AND the operation.
        List<ILoggingEvent> warnings = capturedEventsAt(Level.WARN);
        assertThat(warnings)
            .as("collision must emit exactly one WARN log")
            .hasSize(1);
        String message = warnings.get(0).getFormattedMessage();
        assertThat(message)
            .contains(HonchoOperation.LIST_PEERS.name())
            .contains(alpha.getClass().getName())
            .contains(beta.getClass().getName())
            .contains("first-registered")
            .contains(alpha.getClass().getName()); // "keeping <alpha>" tail
    }

    @Test
    void missingOperationThrowsHelpful() {
        // Minimal registry: only LIST_PEERS is covered. Request
        // SCHEDULE_DREAM (a real HonchoOperation enum constant, not in this
        // registry) — should throw IllegalStateException whose message
        // names BOTH the missing op and the covered one so an operator can
        // diagnose the gap at a glance.
        HonchoProvider onlyListPeers = v3(EnumSet.of(HonchoOperation.LIST_PEERS));
        HonchoProviderRegistry registry = new HonchoProviderRegistry(
            HonchoApiVersion.V3, List.of(onlyListPeers));

        assertThatThrownBy(() -> registry.get(HonchoOperation.SCHEDULE_DREAM))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(HonchoOperation.SCHEDULE_DREAM.name())
            .hasMessageContaining("HonchoProviderRegistry(version=V3)")
            .hasMessageContaining(HonchoOperation.LIST_PEERS.name())
            .hasMessageContaining("Covered operations");
    }

    @Test
    void coversReturnsTrueForRegisteredOpAndFalseOtherwise() {
        HonchoProvider twoOpProvider = v3(EnumSet.of(HonchoOperation.LIST_PEERS, HonchoOperation.CREATE_PEER));
        HonchoProviderRegistry registry = new HonchoProviderRegistry(
            HonchoApiVersion.V3, List.of(twoOpProvider));

        assertThat(registry.covers(HonchoOperation.LIST_PEERS)).isTrue();
        assertThat(registry.covers(HonchoOperation.CREATE_PEER)).isTrue();
        assertThat(registry.covers(HonchoOperation.SCHEDULE_DREAM)).isFalse();
        assertThat(registry.covers(HonchoOperation.GET_WORKSPACE_INFO)).isFalse();
    }

    @Test
    void coveredOperationsReturnsAllRegistered() {
        // Two providers contributing distinct, non-overlapping op sets —
        // 3 ops total, all should show up in coveredOperations().
        HonchoProvider a = v3(EnumSet.of(HonchoOperation.LIST_PEERS, HonchoOperation.CREATE_PEER));
        HonchoProvider b = v3(EnumSet.of(HonchoOperation.SEARCH_MESSAGES));
        HonchoProviderRegistry registry = new HonchoProviderRegistry(
            HonchoApiVersion.V3, List.of(a, b));

        assertThat(registry.coveredOperations())
            .containsExactlyInAnyOrder(
                HonchoOperation.LIST_PEERS,
                HonchoOperation.CREATE_PEER,
                HonchoOperation.SEARCH_MESSAGES);
        assertThat(registry.providerCount())
            .as("distinct provider instances, NOT operations (2 here, 3 ops)")
            .isEqualTo(2);
    }

    private List<ILoggingEvent> capturedEventsAt(Level level) {
        return logCapture.list.stream()
            .filter(e -> e.getLevel().equals(level))
            .toList();
    }

    /**
     * Named static fixture for the collision test. Class name
     * {@code AlphaListPeersProvider} sorts BEFORE
     * {@code BetaListPeersProvider} so the registry picks it as the
     * "first-registered" winner.
     */
    static class AlphaListPeersProvider implements HonchoProvider {
        @Override public Set<HonchoOperation> operations() { return EnumSet.of(HonchoOperation.LIST_PEERS); }
        @Override public Set<HonchoApiVersion> supportedVersions() { return EnumSet.of(HonchoApiVersion.V3); }
        @Override public Object execute(HonchoOperation op, HonchoContext ctx, HonchoClient client,
                                       Object requestBody, Map<String, String> pathVars,
                                       Map<String, ?> queryParams) {
            throw new UnsupportedOperationException("test fixture does not execute " + op);
        }
    }

    /**
     * Named static fixture for the collision test. Class name sorts AFTER
     * {@link AlphaListPeersProvider} so the registry logs it as the loser
     * of the collision.
     */
    static class BetaListPeersProvider implements HonchoProvider {
        @Override public Set<HonchoOperation> operations() { return EnumSet.of(HonchoOperation.LIST_PEERS); }
        @Override public Set<HonchoApiVersion> supportedVersions() { return EnumSet.of(HonchoApiVersion.V3); }
        @Override public Object execute(HonchoOperation op, HonchoContext ctx, HonchoClient client,
                                       Object requestBody, Map<String, String> pathVars,
                                       Map<String, ?> queryParams) {
            throw new UnsupportedOperationException("test fixture does not execute " + op);
        }
    }
}
