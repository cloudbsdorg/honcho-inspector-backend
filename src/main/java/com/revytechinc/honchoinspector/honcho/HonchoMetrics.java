package com.revytechinc.honchoinspector.honcho;

import com.revytechinc.honchoinspector.model.HonchoContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * First-class Micrometer counters for the three workspace actions the
 * Inspector UI's dashboard reports as "all-time count" KPIs:
 *
 * <ul>
 *   <li>{@link #SEARCHES_COUNTER_NAME  honcho.inspector.searches} — total
 *       workspace-wide semantic searches handled by the backend
 *       (corresponds to {@code POST /api/search}).</li>
 *   <li>{@link #DREAMS_COUNTER_NAME honcho.inspector.dreams.scheduled}
 *       — total dreams scheduled by the backend (corresponds to
 *       {@code POST /api/dream}), tagged by the requesting observer
 *       peer id when available.</li>
 *   <li>{@link #MESSAGES_COUNTER_NAME honcho.inspector.messages.sent}
 *       — total messages appended to a session by the backend
 *       (corresponds to {@code POST /api/sessions/{sessionId}/messages}),
 *       tagged by the target session id. <strong>Deprecated for
 *       dashboard display purposes as of the workspace-message-count
 *       fix:</strong> this counter only tracks POSTs through this
 *       specific backend instance since the last process start — it
 *       ignores messages added by prior backend instances, direct
 *       Honcho API calls, and other Honcho clients. The card on the
 *       dashboard no longer reads it. Retained for backward
 *       compatibility (any external scrapers or read-paths that still
 *       key on the name will keep working) — see
 *       {@link #WORKSPACE_MESSAGE_COUNT_NAME} for the Honcho-sourced
 *       replacement that the dashboard now displays.</li>
 *   <li>{@link #PEERS_LISTED_COUNTER_NAME honcho.inspector.peers.listed}
 *       — total peer-list responses served by the backend
 *       (corresponds to {@code GET /api/peers}). Untagged — a single
 *       workspace-wide total matches what the dashboard's "all-time
 *       count" KPI cards display.</li>
 *   <li>{@link #SESSIONS_LISTED_COUNTER_NAME honcho.inspector.sessions.listed}
 *       — total session-list responses served by the backend
 *       (corresponds to {@code GET /api/sessions}). Untagged for the
 *       same reason as peers.</li>
 *   <li>{@link #PROFILES_TESTED_COUNTER_NAME honcho.inspector.profiles.tested}
 *       — total successful "test that this profile can reach Honcho"
 *       checks (corresponds to {@code POST /api/profiles/{id}/test}).
 *       Only the {@code ok: true} reachable path increments — failed
 *       connectivity probes are deliberately excluded so the operator
 *       sees real green-path successes, not retry counts.</li>
 * </ul>
 *
 * <p>In addition to the Micrometer counters above, this class also
 * exposes {@link #WORKSPACE_MESSAGE_COUNT_NAME workspace.messageCount}
 * — a per-profile {@code double} value sourced LIVE from Honcho by
 * summing {@code Page[Message].total} across every session in the
 * workspace. See {@link #workspaceMessageCount(String, HonchoContext)}
 * for the accessor and the 60-second per-profile cache that fronts
 * the upstream call.
 *
 * <p><strong>Why this class exists.</strong> The dashboard originally
 * derived these counts from Spring Boot Actuator's
 * {@code http.server.requests} metric by tagging on the request URI.
 * That metric is registered by
 * {@code org.springframework.boot.actuate.autoconfigure.metrics.web
 * .WebMvcMetricsAutoConfiguration} (pulled in via
 * {@code spring-boot-starter-actuator} + an embedded servlet
 * container). On some actuator / Jetty combinations the metric is
 * either not registered or only updated after a delayed flush, which
 * caused the dashboard's three "all-time count" KPI cards to silently
 * fall back to stale values. Replacing those URI-tagged counters with
 * a stable, first-class counter surface owned by the proxy means the
 * values the operator sees in the dashboard are exactly the values
 * that landed in the proxy — no pending-buffer surprises, no missing
 * metric, no empty-measurements-array silent zero.
 *
 * <p>The counters are exposed automatically by Spring Boot Actuator at
 * {@code GET /actuator/metrics/honcho.inspector.searches} (etc.) in the
 * same shape as any other Micrometer counter:
 * {@code {name, measurements: [{statistic:"COUNT", value:N}, ...]}}.
 *
 * <p><strong>Persistence:</strong> these counters are
 * <em>process-lifetime</em>, exactly like {@code http.server.requests}
 * itself. They reset on backend restart. We intentionally do not
 * persist them — Micrometer's in-memory {@code SimpleMeterRegistry}
 * / Jetty registry is bounded to the running JVM and writing a
 * persistent counter would require a new on-disk store, a startup
 * recovery path, and a write path on every increment (which doubles
 * the latency of the most common code path). The operator-facing
 * "process-lifetime" caveat is documented per-card in the dashboard
 * UI's info popovers; the long-standing user complaint about the KPI
 * cards was numerical flicker (which this fix resolves), not
 * about-restart survival.
 *
 * <p><strong>Failure semantics.</strong> the {@link #recordSearch()},
 * {@link #recordDream(String)}, {@link #recordMessageSent(String)},
 * {@link #recordPeersListed()}, {@link #recordSessionsListed()}, and
 * {@link #recordProfilesTested()} methods only ever increment — they
 * never throw and never fail. A missing tag or null tag value falls
 * back to "unknown" so a misconfigured upstream body can never 5xx the
 * proxy. The {@link #workspaceMessageCount(String, HonchoContext)}
 * accessor (which talks to Honcho) takes a different position: on
 * Honcho failure it logs a WARN and returns either the prior cached
 * value (stale 60s) or {@code 0.0} (no prior cache). A 5xx here would
 * break the dashboard for an entire upstream outage; serving a stale
 * number is the lesser evil — the operator still sees a plausible
 * value, and the dashboard's info popover documents the 60s refresh
 * cadence so the staleness is explicit.
 */
@Component
public class HonchoMetrics {

    private static final Logger log = LoggerFactory.getLogger(HonchoMetrics.class);

    /** Counter name for workspace-wide semantic searches. */
    public static final String SEARCHES_COUNTER_NAME = "honcho.inspector.searches";

    /** Counter name for scheduled dreams. */
    public static final String DREAMS_COUNTER_NAME = "honcho.inspector.dreams.scheduled";

    /**
     * Counter name for messages appended to sessions via this backend.
     * @deprecated for dashboard display; this only tracks POSTs through
     *     this proxy since the last process start. The dashboard card
     *     now reads {@link #WORKSPACE_MESSAGE_COUNT_NAME} (the
     *     Honcho-sourced total). Retained for backward compatibility.
     */
    @Deprecated
    public static final String MESSAGES_COUNTER_NAME = "honcho.inspector.messages.sent";

    /** Counter name for peer-list responses served by the backend. */
    public static final String PEERS_LISTED_COUNTER_NAME = "honcho.inspector.peers.listed";

    /** Counter name for session-list responses served by the backend. */
    public static final String SESSIONS_LISTED_COUNTER_NAME = "honcho.inspector.sessions.listed";

    /** Counter name for successful ("reachable") profile-test responses. */
    public static final String PROFILES_TESTED_COUNTER_NAME = "honcho.inspector.profiles.tested";

    /**
     * Counter-style key for the LIVE Honcho-sourced total message count
     * in the active profile's workspace. The dashboard renders this on
     * the "Messages in workspace" KPI card. Cache: per-profile,
     * 60-second TTL.
     */
    public static final String WORKSPACE_MESSAGE_COUNT_NAME = "workspace.messageCount";

    /**
     * TTL on the {@link #WORKSPACE_MESSAGE_COUNT_NAME} per-profile cache.
     * 60s is a deliberate trade-off: long enough that 20 sessions
     * (each a separate Honcho round-trip) are not re-issued when an
     * operator hits refresh twice in a row, short enough that a freshly
     * added message shows up within a minute.
     */
    static final long WORKSPACE_MESSAGE_COUNT_CACHE_TTL_MS = 60_000L;

    private final MeterRegistry registry;
    private final HonchoClientFactory clientFactory;

    /**
     * Per-profile cache of the Honcho workspace message count. Keyed
     * by the profile id from the active {@link HonchoContext}; cached
     * per profile because each profile may address a different Honcho
     * workspace. {@link ConcurrentHashMap} keeps concurrent dashboard
     * refreshes safe (without it the read-then-write pattern in
     * {@link #workspaceMessageCount} could lose updates or trigger a
     * redundant fan-out under load).
     */
    private final Map<String, CachedWorkspaceMessageCount> workspaceMessageCountCache = new ConcurrentHashMap<>();

    public HonchoMetrics(MeterRegistry registry, HonchoClientFactory clientFactory) {
        this.registry = registry;
        this.clientFactory = clientFactory;
    }

    /**
     * Increment the workspace-search counter. Called once per
     * successful {@code POST /api/search} (HTTP 2xx) from
     * {@code HonchoController.workspaceSearch}.
     */
    public void recordSearch() {
        // No tag space: a successful search is already a successful
        // workspace-wide search; splitting by observer would only
        // fragment the existing /api/search dashboard card.
        registry.counter(SEARCHES_COUNTER_NAME).increment();
    }

    /**
     * Increment the dream-scheduled counter. Called once per
     * successful {@code POST /api/dream} (HTTP 2xx) from
     * {@code HonchoController.scheduleDream}. The
     * {@code observer} tag carries the peerId that body of the
     * request identified as the dream's target — this lets the
     * admin diagnostics surface and Prometheus queries slice dreams
     * per peer without re-deriving from the audit log.
     *
     * <p>A null or blank {@code observer} is recorded under the
     * {@code "unknown"} tag so the call still increments and the
     * dashboard "all-time count" stays accurate even when upstream
     * omits the body field we expected.
     */
    public void recordDream(String observer) {
        registry.counter(DREAMS_COUNTER_NAME, "observer", safeTag(observer)).increment();
    }

    /**
     * Increment the message-sent counter. Called once per
     * successful {@code POST /api/sessions/{sessionId}/messages}
     * (HTTP 2xx) from {@code HonchoController.addMessages}. The
     * {@code session} tag carries the path-variable session id. A
     * null session is recorded under {@code "unknown"} (same
     * rationale as {@link #recordDream(String)}).
     */
    public void recordMessageSent(String sessionId) {
        registry.counter(MESSAGES_COUNTER_NAME, "session", safeTag(sessionId)).increment();
    }

    /**
     * Increment the peers-listed counter. Called once per
     * successful {@code GET /api/peers} (HTTP 2xx) from
     * {@code HonchoController.listPeers}. Untagged — the dashboard
     * reports a single workspace-wide total.
     */
    public void recordPeersListed() {
        registry.counter(PEERS_LISTED_COUNTER_NAME).increment();
    }

    /**
     * Increment the sessions-listed counter. Called once per
     * successful {@code GET /api/sessions} (HTTP 2xx) from
     * {@code HonchoController.listSessions}. Untagged for the same
     * reason as {@link #recordPeersListed()}.
     */
    public void recordSessionsListed() {
        registry.counter(SESSIONS_LISTED_COUNTER_NAME).increment();
    }

    /**
     * Increment the profiles-tested counter. Called once per
     * successful {@code POST /api/profiles/{id}/test} (HTTP 2xx with
     * the {@code ok: true, message: "reachable"} body) from
     * {@code ProfileController.test}. Failed probes
     * ({@code ok: false}) deliberately do NOT increment — the
     * dashboard's "all-time tested" KPI card shows real
     * green-path successes, not retry counts.
     */
    public void recordProfilesTested() {
        registry.counter(PROFILES_TESTED_COUNTER_NAME).increment();
    }

    /**
     * Resolve the {@link Counter} behind each named counter. Test
     * code uses these to assert increment-without-side-effects
     * without having to load the full Spring context.
     */
    public Counter searchesCounter() {
        return registry.counter(SEARCHES_COUNTER_NAME);
    }

    public Counter dreamsCounter(String observer) {
        return registry.counter(DREAMS_COUNTER_NAME, "observer", safeTag(observer));
    }

    public Counter messagesCounter(String sessionId) {
        return registry.counter(MESSAGES_COUNTER_NAME, "session", safeTag(sessionId));
    }

    public Counter peersListedCounter() {
        return registry.counter(PEERS_LISTED_COUNTER_NAME);
    }

    public Counter sessionsListedCounter() {
        return registry.counter(SESSIONS_LISTED_COUNTER_NAME);
    }

    public Counter profilesTestedCounter() {
        return registry.counter(PROFILES_TESTED_COUNTER_NAME);
    }

    /**
     * Sum of {@link #DREAMS_COUNTER_NAME} across every distinct
     * {@code observer} tag value. Used by
     * {@code WorkspaceMetricsController} to return one "total dreams
     * scheduled" number for the dashboard, since the underlying
     * counter is tagged per-observer.
     *
     * <p>Returns {@code 0.0} when no dreams have been recorded
     * yet (the {@link io.micrometer.core.instrument.search.Search}
     * yields an empty stream). Safe to call on a fresh registry.
     */
    public double dreamsCounterTotal() {
        return registry.find(DREAMS_COUNTER_NAME).counters().stream()
            .mapToDouble(Counter::count)
            .sum();
    }

    /**
     * Sum of {@link #MESSAGES_COUNTER_NAME} across every distinct
     * {@code session} tag value. See {@link #dreamsCounterTotal()}
     * for the rationale.
     */
    public double messagesCounterTotal() {
        return registry.find(MESSAGES_COUNTER_NAME).counters().stream()
            .mapToDouble(Counter::count)
            .sum();
    }

    /**
     * Return the LIVE Honcho workspace message count for the profile
     * identified by {@code profileId}, served through a per-profile
     * {@link #WORKSPACE_MESSAGE_COUNT_CACHE_TTL_MS 60-second cache}
     * that fronts an otherwise potentially expensive fan-out
     * (one round-trip per session).
     *
     * <p>The dashboard card this backs reflects actual Honcho state —
     * messages added by any path count (this backend, prior instances,
     * direct Honcho API, other clients). This replaced the older
     * {@link #MESSAGES_COUNTER_NAME} which silently reported {@code 0}
     * whenever the user added messages via any non-proxy path; see
     * the class Javadoc for the architectural rationale.
     *
     * <p>Failure semantics: on Honcho failure (network error, 4xx,
     * 5xx) the call is logged at WARN and the method returns EITHER
     * the prior cached value if one exists, OR {@code 0.0} for a cold
     * cache. The dashboard therefore never receives a 5xx from this
     * accessor — a staleness signal is preferable to a hard failure
     * that blanks the KPI card during an upstream outage.
     *
     * @param profileId the active profile id (cache key). A null /
     *     blank value collapses to a single shared slot so two
     *     concurrent requests without a profile (e.g. an unauthenticated
     *     boot-time ping) still share the cache. Profiles map to
     *     different Honcho workspaces, so conflating them would be
     *     wrong; conflating "no profile known" with the literal string
     *     {@code "<none>"} is fine because that path returns 0 from
     *     the controller when no profile is available.
     * @param ctx       the {@link HonchoContext} for the profile —
     *     passed through to {@link HonchoClient#totalWorkspaceMessages}
     * @return the workspace message count from the cache (if fresh) or
     *     from Honcho (if cache miss / expiry); {@code 0.0} on cold
     *     cache + Honcho failure.
     */
    public double workspaceMessageCount(String profileId, HonchoContext ctx) {
        String key = (profileId == null || profileId.isBlank()) ? "<none>" : profileId;
        long now = System.currentTimeMillis();
        CachedWorkspaceMessageCount cached = workspaceMessageCountCache.get(key);
        if (cached != null && cached.expiresAtMs > now) {
            return cached.value;
        }
        try {
            HonchoClient client = clientFactory.clientFor(ctx.apiVersion());
            double value = client.totalWorkspaceMessages(ctx);
            workspaceMessageCountCache.put(key, new CachedWorkspaceMessageCount(value, now + WORKSPACE_MESSAGE_COUNT_CACHE_TTL_MS));
            return value;
        } catch (Exception e) {
            // Honcho failure — log and degrade. Returning the stale
            // value (if any) is a deliberate trade-off: under a brief
            // upstream blip the operator still sees a plausible KPI
            // rather than a sudden zero. A fresh zero would falsely
            // imply "the workspace has never had any messages", which
            // is almost always wrong for an operator who's been using
            // the system. If there's no prior cache to serve from, we
            // have no choice but to return 0.
            log.warn("workspaceMessageCount Honcho probe failed for profile {}: {}", key, e.toString());
            return cached != null ? cached.value : 0.0;
        }
    }

    /**
     * Small immutable holder for a cached workspace message count and
     * its absolute expiry timestamp (epoch millis). The expiry is
     * absolute rather than relative so we never re-read a stale entry
     * after a long GC pause — the next call reads
     * {@link System#currentTimeMillis()} fresh and compares.
     */
    private record CachedWorkspaceMessageCount(double value, long expiresAtMs) {}

    private static String safeTag(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        return raw;
    }
}
