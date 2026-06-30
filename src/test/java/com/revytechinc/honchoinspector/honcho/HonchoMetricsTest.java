package com.revytechinc.honchoinspector.honcho;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.revytechinc.honchoinspector.model.HonchoContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link HonchoMetrics}. These use {@link SimpleMeterRegistry}
 * so they run without any Spring context — the registry is the same primitive
 * Spring Boot Actuator auto-configures at runtime, just with an in-memory
 * backend instead of the Prometheus exporter.
 *
 * <p>Each test asserts (a) the named counter exists with the right Micrometer
 * name after first increment, (b) the value increments by exactly one per call,
 * and (c) the tag fallback to {@code "unknown"} fires for null/blank inputs.
 * The dashboard KPI cards read
 * {@code GET /actuator/metrics/honcho.inspector.<name>} and look up the
 * {@code COUNT} statistic — this test asserts the same shape.
 *
 * <p>The {@code workspaceMessageCount_*} tests exercise the per-profile
 * cache around {@link HonchoMetrics#workspaceMessageCount(String,
 * HonchoContext)}: cache hit, cache miss → Honcho consult,
 * Honcho failure with prior cache (returns stale), Honcho failure on cold
 * cache (returns {@code 0}).
 */
class HonchoMetricsTest {

    private static final HonchoContext CTX = new HonchoContext(
        "test-api-key", "https://api.honcho.dev", "ws-42", "test-user",
        HonchoApiVersion.V3, "profile-1"
    );

    private SimpleMeterRegistry registry;
    private HonchoClientFactory clientFactory;
    private HonchoClient client;
    private HonchoMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        client = mock(HonchoClient.class);
        clientFactory = mock(HonchoClientFactory.class);
        // Default factory behavior: return the mocked client for any
        // version. Each workspaceMessageCount_* test overrides this
        // for failure simulations.
        when(clientFactory.clientFor(any(HonchoApiVersion.class))).thenReturn(client);
        metrics = new HonchoMetrics(registry, clientFactory);
    }

    @Test
    void recordSearch_createsCounterWithExpectedName() {
        metrics.recordSearch();

        assertThat(registry.find(HonchoMetrics.SEARCHES_COUNTER_NAME).counter())
            .as("a successful POST /api/search should increment honcho.inspector.searches")
            .isNotNull()
            .extracting(c -> c.count())
            .isEqualTo(1.0);
    }

    @Test
    void recordSearch_incrementsByOnePerCall() {
        metrics.recordSearch();
        metrics.recordSearch();
        metrics.recordSearch();

        assertThat(registry.counter(HonchoMetrics.SEARCHES_COUNTER_NAME).count())
            .as("three searches should produce a COUNT of 3.0")
            .isEqualTo(3.0);
    }

    @Test
    void recordDream_createsCounterTaggedByObserver() {
        metrics.recordDream("alice");

        assertThat(registry.find(HonchoMetrics.DREAMS_COUNTER_NAME)
                .tag("observer", "alice")
                .counter())
            .as("a successful POST /api/dream should increment honcho.inspector.dreams.scheduled; observer=alice")
            .isNotNull()
            .extracting(c -> c.count())
            .isEqualTo(1.0);
    }

    @Test
    void recordDream_segregatesCountersByObserverTag() {
        metrics.recordDream("alice");
        metrics.recordDream("alice");
        metrics.recordDream("bob");

        assertThat(registry.counter(HonchoMetrics.DREAMS_COUNTER_NAME, "observer", "alice").count())
            .as("alice dream counter should reflect her two dreams")
            .isEqualTo(2.0);
        assertThat(registry.counter(HonchoMetrics.DREAMS_COUNTER_NAME, "observer", "bob").count())
            .as("bob dream counter should reflect his one dream")
            .isEqualTo(1.0);
    }

    @Test
    void recordDream_fallsBackToUnknownTagWhenObserverNull() {
        // Defensive: a malformed /api/dream body that misses peerId returns
        // 400 BEFORE this method runs (see HonchoController.scheduleDream),
        // so null here would only happen if we ever loosen the controller's
        // contract. The fallback keeps the all-time-count total accurate if
        // that ever changes.
        metrics.recordDream(null);

        assertThat(registry.find(HonchoMetrics.DREAMS_COUNTER_NAME)
                .tag("observer", "unknown")
                .counter())
            .as("a null observer should record under the 'unknown' tag, not crash the dashboard counter")
            .isNotNull()
            .extracting(c -> c.count())
            .isEqualTo(1.0);
    }

    @Test
    void recordDream_fallsBackToUnknownTagWhenObserverBlank() {
        metrics.recordDream("   ");

        assertThat(registry.find(HonchoMetrics.DREAMS_COUNTER_NAME)
                .tag("observer", "unknown")
                .counter())
            .isNotNull()
            .extracting(c -> c.count())
            .isEqualTo(1.0);
    }

    @Test
    void recordMessageSent_createsCounterTaggedBySession() {
        metrics.recordMessageSent("sess_abc");

        assertThat(registry.find(HonchoMetrics.MESSAGES_COUNTER_NAME)
                .tag("session", "sess_abc")
                .counter())
            .isNotNull()
            .extracting(c -> c.count())
            .isEqualTo(1.0);
    }

    @Test
    void recordMessageSent_segregatesBySessionTag() {
        metrics.recordMessageSent("sess_abc");
        metrics.recordMessageSent("sess_abc");
        metrics.recordMessageSent("sess_def");

        assertThat(registry.counter(HonchoMetrics.MESSAGES_COUNTER_NAME, "session", "sess_abc").count())
            .isEqualTo(2.0);
        assertThat(registry.counter(HonchoMetrics.MESSAGES_COUNTER_NAME, "session", "sess_def").count())
            .isEqualTo(1.0);
    }

    @Test
    void recordMessageSent_fallsBackToUnknownTagWhenSessionNull() {
        metrics.recordMessageSent(null);

        assertThat(registry.find(HonchoMetrics.MESSAGES_COUNTER_NAME)
                .tag("session", "unknown")
                .counter())
            .isNotNull()
            .extracting(c -> c.count())
            .isEqualTo(1.0);
    }

    @Test
    void allThreeCounterNamesAreExposedAndIndependent() {
        // The dashboard reads each named counter via GET /actuator/metrics/<name>;
        // ensure none of the three is shadowed by another and each responds to
        // its increment separately.
        metrics.recordSearch();
        metrics.recordDream("alice");
        metrics.recordMessageSent("sess_abc");

        assertThat(registry.counter(HonchoMetrics.SEARCHES_COUNTER_NAME).count()).isEqualTo(1.0);
        assertThat(registry.counter(HonchoMetrics.DREAMS_COUNTER_NAME, "observer", "alice").count()).isEqualTo(1.0);
        assertThat(registry.counter(HonchoMetrics.MESSAGES_COUNTER_NAME, "session", "sess_abc").count()).isEqualTo(1.0);

        // Sanity: the named counters hold the exact same names callers will
        // hit via /actuator/metrics. If a future refactor changes a constant
        // here, every dashboard KPI card silently breaks — this constant
        // guards the contract.
        assertThat(HonchoMetrics.SEARCHES_COUNTER_NAME).isEqualTo("honcho.inspector.searches");
        assertThat(HonchoMetrics.DREAMS_COUNTER_NAME).isEqualTo("honcho.inspector.dreams.scheduled");
        assertThat(HonchoMetrics.MESSAGES_COUNTER_NAME).isEqualTo("honcho.inspector.messages.sent");
    }

    @Test
    void recordPeersListed_incrementsByOnePerCall() {
        metrics.recordPeersListed();
        metrics.recordPeersListed();
        metrics.recordPeersListed();

        assertThat(registry.counter(HonchoMetrics.PEERS_LISTED_COUNTER_NAME).count())
            .as("three successful GET /api/peers should produce a COUNT of 3.0")
            .isEqualTo(3.0);
    }

    @Test
    void recordPeersListed_createsCounterWithExpectedName() {
        metrics.recordPeersListed();

        assertThat(registry.find(HonchoMetrics.PEERS_LISTED_COUNTER_NAME).counter())
            .as("a successful GET /api/peers should increment honcho.inspector.peers.listed")
            .isNotNull()
            .extracting(c -> c.count())
            .isEqualTo(1.0);
        assertThat(HonchoMetrics.PEERS_LISTED_COUNTER_NAME).isEqualTo("honcho.inspector.peers.listed");
    }

    @Test
    void recordSessionsListed_incrementsByOnePerCall() {
        metrics.recordSessionsListed();
        metrics.recordSessionsListed();

        assertThat(registry.counter(HonchoMetrics.SESSIONS_LISTED_COUNTER_NAME).count())
            .as("two successful GET /api/sessions should produce a COUNT of 2.0")
            .isEqualTo(2.0);
    }

    @Test
    void recordSessionsListed_createsCounterWithExpectedName() {
        metrics.recordSessionsListed();

        assertThat(registry.find(HonchoMetrics.SESSIONS_LISTED_COUNTER_NAME).counter())
            .isNotNull()
            .extracting(c -> c.count())
            .isEqualTo(1.0);
        assertThat(HonchoMetrics.SESSIONS_LISTED_COUNTER_NAME).isEqualTo("honcho.inspector.sessions.listed");
    }

    @Test
    void recordProfilesTested_incrementsByOnePerCall() {
        metrics.recordProfilesTested();
        metrics.recordProfilesTested();
        metrics.recordProfilesTested();
        metrics.recordProfilesTested();

        assertThat(registry.counter(HonchoMetrics.PROFILES_TESTED_COUNTER_NAME).count())
            .as("four successful POST /api/profiles/{id}/test probes should produce a COUNT of 4.0")
            .isEqualTo(4.0);
    }

    @Test
    void recordProfilesTested_createsCounterWithExpectedName() {
        metrics.recordProfilesTested();

        assertThat(registry.find(HonchoMetrics.PROFILES_TESTED_COUNTER_NAME).counter())
            .as("a successful profile connectivity probe should increment honcho.inspector.profiles.tested")
            .isNotNull()
            .extracting(c -> c.count())
            .isEqualTo(1.0);
        assertThat(HonchoMetrics.PROFILES_TESTED_COUNTER_NAME).isEqualTo("honcho.inspector.profiles.tested");
    }

    @Test
    void dreamsCounterTotal_sumsAcrossObserverTags() {
        // Mirrors the WorkspaceMetricsControllerTest assertion shape: two dreams
        // for alice + one for bob = 3 total across the dashboard's KPI card.
        metrics.recordDream("alice");
        metrics.recordDream("alice");
        metrics.recordDream("bob");

        assertThat(metrics.dreamsCounterTotal())
            .as("dreams.scheduled total should sum across every observer tag")
            .isEqualTo(3.0);
        assertThat(registry.counter(HonchoMetrics.DREAMS_COUNTER_NAME, "observer", "alice").count())
            .as("per-tag sanity: alice counter is 2")
            .isEqualTo(2.0);
        assertThat(registry.counter(HonchoMetrics.DREAMS_COUNTER_NAME, "observer", "bob").count())
            .as("per-tag sanity: bob counter is 1")
            .isEqualTo(1.0);
    }

    @Test
    void dreamsCounterTotal_isZeroOnFreshRegistry() {
        // The dashboard reads this endpoint on first page load before any
        // /api/dream has fired; the counter must exist (zero, not null /
        // missing) so the JSON shape stays stable.
        assertThat(metrics.dreamsCounterTotal()).isEqualTo(0.0);
    }

    @Test
    void messagesCounterTotal_sumsAcrossSessionTags() {
        metrics.recordMessageSent("sess_abc");
        metrics.recordMessageSent("sess_abc");
        metrics.recordMessageSent("sess_def");
        metrics.recordMessageSent("sess_ghi");

        assertThat(metrics.messagesCounterTotal())
            .as("messages.sent total should sum across every session tag")
            .isEqualTo(4.0);
    }

    @Test
    void messagesCounterTotal_isZeroOnFreshRegistry() {
        assertThat(metrics.messagesCounterTotal()).isEqualTo(0.0);
    }

    @Test
    void allSixCounterNamesAreExposedAndIndependent() {
        // Sanity: the six dashboard-counter constants hold the exact same
        // names callers will hit. If a future refactor changes a constant
        // here, every dashboard KPI card silently breaks — this constant
        // guards the contract.
        assertThat(HonchoMetrics.SEARCHES_COUNTER_NAME).isEqualTo("honcho.inspector.searches");
        assertThat(HonchoMetrics.DREAMS_COUNTER_NAME).isEqualTo("honcho.inspector.dreams.scheduled");
        assertThat(HonchoMetrics.MESSAGES_COUNTER_NAME).isEqualTo("honcho.inspector.messages.sent");
        assertThat(HonchoMetrics.PEERS_LISTED_COUNTER_NAME).isEqualTo("honcho.inspector.peers.listed");
        assertThat(HonchoMetrics.SESSIONS_LISTED_COUNTER_NAME).isEqualTo("honcho.inspector.sessions.listed");
        assertThat(HonchoMetrics.PROFILES_TESTED_COUNTER_NAME).isEqualTo("honcho.inspector.profiles.tested");

        // And each named counter increments independently.
        metrics.recordSearch();
        metrics.recordDream("alice");
        metrics.recordMessageSent("sess_abc");
        metrics.recordPeersListed();
        metrics.recordSessionsListed();
        metrics.recordProfilesTested();

        assertThat(registry.counter(HonchoMetrics.SEARCHES_COUNTER_NAME).count()).isEqualTo(1.0);
        assertThat(metrics.dreamsCounterTotal()).isEqualTo(1.0);
        assertThat(metrics.messagesCounterTotal()).isEqualTo(1.0);
        assertThat(registry.counter(HonchoMetrics.PEERS_LISTED_COUNTER_NAME).count()).isEqualTo(1.0);
        assertThat(registry.counter(HonchoMetrics.SESSIONS_LISTED_COUNTER_NAME).count()).isEqualTo(1.0);
        assertThat(registry.counter(HonchoMetrics.PROFILES_TESTED_COUNTER_NAME).count()).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------
    // workspaceMessageCount — cache + failure semantics around the
    // Honcho-sourced total. Backed by a mocked HonchoClient +
    // HonchoClientFactory so the per-profile cache logic is exercised
    // in isolation.
    // ------------------------------------------------------------------

    @Test
    void workspaceMessageCount_cachesFor60Seconds_consultsHonchoOnceForBackToBackCalls() {
        // First call: cold cache → Honcho consulted.
        when(client.totalWorkspaceMessages(CTX)).thenReturn(42.0);
        assertThat(metrics.workspaceMessageCount("profile-1", CTX)).isEqualTo(42.0);

        // Second call within the 60s TTL: cache hit → Honcho NOT
        // consulted again. We bump the mock's return value to prove
        // the second read came from the cache, not Honcho.
        when(client.totalWorkspaceMessages(CTX)).thenReturn(999.0);
        assertThat(metrics.workspaceMessageCount("profile-1", CTX)).isEqualTo(42.0);

        verify(client, times(1)).totalWorkspaceMessages(CTX);
    }

    @Test
    void workspaceMessageCount_handlesHonchoFailure_returnsStaleCache() {
        // Prime the cache with a successful Honcho call.
        when(client.totalWorkspaceMessages(CTX)).thenReturn(42.0);
        assertThat(metrics.workspaceMessageCount("profile-1", CTX)).isEqualTo(42.0);

        // Within the TTL, Honcho now fails. We expect the stale 42.0
        // to be served (better than blanking the card with a 0).
        when(client.totalWorkspaceMessages(CTX))
            .thenThrow(new HonchoCallException("upstream 502", 502, "transport"));
        assertThat(metrics.workspaceMessageCount("profile-1", CTX)).isEqualTo(42.0);
    }

    @Test
    void workspaceMessageCount_handlesHonchoFailure_returnsZeroOnColdCache() {
        // No prior call, cache is cold, Honcho fails. We have no stale
        // value to serve, so 0.0 is the only honest answer (the
        // dashboard's "show 0 if no data" UX).
        when(client.totalWorkspaceMessages(CTX))
            .thenThrow(new HonchoCallException("upstream 502", 502, "transport"));

        assertThat(metrics.workspaceMessageCount("profile-1", CTX)).isEqualTo(0.0);
    }

    @Test
    void workspaceMessageCount_keyedByProfile_consultsHonchoForEachNewProfile() {
        // Two profiles, two different Honcho workspaces. The cache
        // must key by profileId so a value for profile-A does NOT
        // leak across to profile-B (they map to different Honcho
        // workspaces with potentially different message totals).
        when(client.totalWorkspaceMessages(any(HonchoContext.class))).thenReturn(10.0);
        HonchoContext profileA = new HonchoContext(
            "key-a", "https://api.honcho.dev", "ws-a", "u", HonchoApiVersion.V3, "profile-A");
        HonchoContext profileB = new HonchoContext(
            "key-b", "https://api.honcho.dev", "ws-b", "u", HonchoApiVersion.V3, "profile-B");

        assertThat(metrics.workspaceMessageCount("profile-A", profileA)).isEqualTo(10.0);
        assertThat(metrics.workspaceMessageCount("profile-A", profileA)).isEqualTo(10.0);
        assertThat(metrics.workspaceMessageCount("profile-B", profileB)).isEqualTo(10.0);
        assertThat(metrics.workspaceMessageCount("profile-B", profileB)).isEqualTo(10.0);

        ArgumentCaptor<HonchoContext> ctxCaptor = ArgumentCaptor.forClass(HonchoContext.class);
        verify(client, times(2)).totalWorkspaceMessages(ctxCaptor.capture());
        Set<HonchoContext> ctxs = new HashSet<>(ctxCaptor.getAllValues());
        assertThat(ctxs).as("each profile id must trigger exactly one Honcho call (cache miss per profile)")
            .containsExactlyInAnyOrder(profileA, profileB);
    }

    @Test
    void workspaceMessageCount_blankProfileId_collapsesToSharedSlot() {
        // A profile-less ping (e.g. before the frontend has chosen an
        // active profile) must not crash and must share the cache slot
        // so two concurrent profile-less requests don't double the
        // Honcho load.
        when(client.totalWorkspaceMessages(CTX)).thenReturn(7.0);
        assertThat(metrics.workspaceMessageCount(null, CTX)).isEqualTo(7.0);
        assertThat(metrics.workspaceMessageCount("", CTX)).isEqualTo(7.0);
        assertThat(metrics.workspaceMessageCount("   ", CTX)).isEqualTo(7.0);

        verify(client, times(1)).totalWorkspaceMessages(CTX);
    }

    @Test
    void workspaceMessageCount_usesFactoryToResolveV3Client() {
        // Defensive: even if we ever have multiple HonchoClient
        // implementations registered, the accessor must consult the
        // factory with the context's apiVersion rather than hardcoding
        // a particular client. Asserts the factory is consulted exactly
        // once per cache miss.
        when(client.totalWorkspaceMessages(CTX)).thenReturn(13.0);
        assertThat(metrics.workspaceMessageCount("profile-1", CTX)).isEqualTo(13.0);

        verify(clientFactory, times(1)).clientFor(HonchoApiVersion.V3);
        verify(client, times(1)).totalWorkspaceMessages(CTX);
    }

    @Test
    void workspaceMessageCount_constantHoldsTheDashboardKey() {
        // Sanity guard: a future refactor that changes this string
        // will break the dashboard's KPI card silently. The constant
        // holds the public contract.
        assertThat(HonchoMetrics.WORKSPACE_MESSAGE_COUNT_NAME).isEqualTo("workspace.messageCount");
    }
}
