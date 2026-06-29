package com.revytechinc.honchoinspector.honcho;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 */
class HonchoMetricsTest {

    private SimpleMeterRegistry registry;
    private HonchoMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new HonchoMetrics(registry);
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
}
