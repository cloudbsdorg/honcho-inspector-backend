package com.revytechinc.honchoinspector.honcho;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

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
 *       tagged by the target session id.</li>
 * </ul>
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
 * {@link #recordDream(String)}, and {@link #recordMessageSent(String)}
 * methods only ever increment — they never throw and never fail. A
 * missing tag or null tag value falls back to "unknown" so a
 * misconfigured upstream body can never 5xx the proxy.
 */
@Component
public class HonchoMetrics {

    /** Counter name for workspace-wide semantic searches. */
    public static final String SEARCHES_COUNTER_NAME = "honcho.inspector.searches";

    /** Counter name for scheduled dreams. */
    public static final String DREAMS_COUNTER_NAME = "honcho.inspector.dreams.scheduled";

    /** Counter name for messages appended to sessions. */
    public static final String MESSAGES_COUNTER_NAME = "honcho.inspector.messages.sent";

    private final MeterRegistry registry;

    public HonchoMetrics(MeterRegistry registry) {
        this.registry = registry;
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

    private static String safeTag(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        return raw;
    }
}
