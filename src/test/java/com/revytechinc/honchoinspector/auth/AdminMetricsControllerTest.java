package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.honcho.HonchoMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@code /api/admin/metrics/counters}. The endpoint was
 * previously owned by {@code AdminMetricsController} (with class-level
 * {@code @RequireAdmin}); it now lives on {@link WorkspaceMetricsController}
 * with no admin gate because the values it returns are workspace-level
 * aggregates already reachable via {@code /api/peers}, {@code /api/sessions},
 * and the Honcho proxy. This test class is kept as a black-box smoke
 * test against the URL itself, focused on response shape and counter
 * arithmetic — the role/auth matrix now lives in
 * {@code WorkspaceMetricsControllerTest}.
 *
 * <p>Unlike {@code AdminDiagnosticsControllerTest}, this relay reads
 * directly from the in-memory Micrometer registry (no
 * {@code /actuator} loopback), so these tests assert the SHAPE
 * of the response the dashboard will see, not Spring Boot
 * Actuator's surface.
 *
 * <p>Uses WebEnvironment.RANDOM_PORT + java.net.http.HttpClient
 * (real HTTP) so the {@code X-Session-Id} filter runs for real.
 * Spring Boot 4 removed {@code TestRestTemplate} in favor of the
 * standard JDK HttpClient, which works fine here.
 *
 * <p>The "sum across tags" assertion exercises the
 * {@link HonchoMetrics#dreamsCounterTotal()} path the dashboard
 * depends on for its "all-time dreams scheduled" KPI card.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "HONCHO_DB_PATH=jdbc:sqlite::memory:",
    "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24="
})
class AdminMetricsControllerTest {

    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordHasher hasher;
    @Autowired HonchoMetrics metrics;
    @Autowired MeterRegistry registry;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private static final SecureRandom RNG = new SecureRandom();

    @BeforeEach
    void cleanDb() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM honcho_profiles");
        jdbc.update("DELETE FROM users");
        // Reset all six counters to 0 so test order doesn't leak state.
        // The HonchoMetrics bean is a process-lifetime singleton; without
        // this reset, an earlier test that touched the bean would leak
        // values into this one. Micrometer's Counter has no subtract API,
        // so we remove() and re-create via the next call to HonchoMetrics.
        registry.find(HonchoMetrics.SEARCHES_COUNTER_NAME).counters()
            .forEach(c -> registry.remove(c));
        registry.find(HonchoMetrics.DREAMS_COUNTER_NAME).counters()
            .forEach(c -> registry.remove(c));
        registry.find(HonchoMetrics.MESSAGES_COUNTER_NAME).counters()
            .forEach(c -> registry.remove(c));
        registry.find(HonchoMetrics.PEERS_LISTED_COUNTER_NAME).counters()
            .forEach(c -> registry.remove(c));
        registry.find(HonchoMetrics.SESSIONS_LISTED_COUNTER_NAME).counters()
            .forEach(c -> registry.remove(c));
        registry.find(HonchoMetrics.PROFILES_TESTED_COUNTER_NAME).counters()
            .forEach(c -> registry.remove(c));
    }

    @Test
    void counters_withoutSession_returns401() throws Exception {
        var resp = http.send(
            HttpRequest.newBuilder()
                .uri(uri("/api/admin/metrics/counters"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    @SuppressWarnings("unchecked")
    void counters_asAdmin_returnsAllSevenCounterKeysAndCapturedAt() throws Exception {
        createUser("admin", true);
        var sid = loginAs("admin", "longpassword");

        var body = fetchCounters(sid);

        assertThat(body).containsKey("counters");
        assertThat(body).containsKey("capturedAt");

        Map<String, Object> counters = (Map<String, Object>) body.get("counters");

        // Exact 7 keys, in the dashboard's stat-card order. If a future
        // refactor adds or removes a counter, the dashboard's per-card
        // mapping silently breaks — this guards the contract. The
        // seventh key (workspace.messageCount) is sourced LIVE from
        // Honcho rather than from the in-memory MeterRegistry; the
        // test exercises the "no profile" path so it returns 0.
        assertThat(counters.keySet())
            .containsExactly(
                "honcho.inspector.searches",
                "honcho.inspector.dreams.scheduled",
                "honcho.inspector.messages.sent",
                "honcho.inspector.peers.listed",
                "honcho.inspector.sessions.listed",
                "honcho.inspector.profiles.tested",
                "workspace.messageCount");

        // Every counter starts at 0 after @BeforeEach reset (and the
        // workspace.messageCount is 0 because no profile exists in
        // this in-memory test).
        for (Object value : counters.values()) {
            assertThat(value).isInstanceOfAny(Double.class, Integer.class, Long.class, Float.class);
            assertThat(((Number) value).doubleValue()).isEqualTo(0.0);
        }

        // capturedAt is an ISO-8601 instant string; verify it parses.
        String capturedAt = (String) body.get("capturedAt");
        assertThat(capturedAt).isNotBlank();
        assertThat(Instant.parse(capturedAt))
            .as("capturedAt must be a valid ISO-8601 instant")
            .isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void counters_workspaceMessageCount_isPresentAndNumeric() throws Exception {
        // Pure-key-shape guard: the dashboard reads counters by name,
        // and a missing workspace.messageCount would silently fall back
        // to 0. This locks the contract: the key IS in the response,
        // AND its value is a number (so `String(value ?? 0)` in the
        // dashboard's `kpis` computed signal doesn't crash on a missing
        // entry).
        createUser("admin", true);
        var sid = loginAs("admin", "longpassword");

        var body = fetchCounters(sid);
        Map<String, Object> counters = (Map<String, Object>) body.get("counters");

        assertThat(counters).containsKey(HonchoMetrics.WORKSPACE_MESSAGE_COUNT_NAME);
        Object value = counters.get(HonchoMetrics.WORKSPACE_MESSAGE_COUNT_NAME);
        assertThat(value)
            .as("workspace.messageCount must be a Number, not null/undefined")
            .isInstanceOfAny(Double.class, Integer.class, Long.class, Float.class);
        assertThat(((Number) value).doubleValue())
            .as("no profile in this in-memory test → workspace.messageCount is 0")
            .isEqualTo(0.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void counters_dreamsScheduled_sumsAcrossObserverTags() throws Exception {
        createUser("admin", true);
        var sid = loginAs("admin", "longpassword");

        // Two dreams for alice + one for bob = 3 total across the
        // dashboard's "dreams scheduled" KPI card.
        metrics.recordDream("alice");
        metrics.recordDream("alice");
        metrics.recordDream("bob");

        var body = fetchCounters(sid);
        Map<String, Object> counters = (Map<String, Object>) body.get("counters");

        assertThat(((Number) counters.get("honcho.inspector.dreams.scheduled")).doubleValue())
            .as("dreams.scheduled must SUM across all observer tag values (alice: 2, bob: 1 = 3)")
            .isEqualTo(3.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void counters_messagesSent_sumsAcrossSessionTags() throws Exception {
        createUser("admin", true);
        var sid = loginAs("admin", "longpassword");

        metrics.recordMessageSent("sess_abc");
        metrics.recordMessageSent("sess_abc");
        metrics.recordMessageSent("sess_def");

        var body = fetchCounters(sid);
        Map<String, Object> counters = (Map<String, Object>) body.get("counters");

        assertThat(((Number) counters.get("honcho.inspector.messages.sent")).doubleValue())
            .as("messages.sent must SUM across all session tag values (abc: 2, def: 1 = 3)")
            .isEqualTo(3.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void counters_untaggedCounters_reflectTheirIncrement() throws Exception {
        createUser("admin", true);
        var sid = loginAs("admin", "longpassword");

        metrics.recordSearch();
        metrics.recordSearch();
        metrics.recordPeersListed();
        metrics.recordSessionsListed();
        metrics.recordProfilesTested();

        var body = fetchCounters(sid);
        Map<String, Object> counters = (Map<String, Object>) body.get("counters");

        assertThat(((Number) counters.get("honcho.inspector.searches")).doubleValue()).isEqualTo(2.0);
        assertThat(((Number) counters.get("honcho.inspector.peers.listed")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) counters.get("honcho.inspector.sessions.listed")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) counters.get("honcho.inspector.profiles.tested")).doubleValue()).isEqualTo(1.0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchCounters(String sid) throws Exception {
        var resp = http.send(
            HttpRequest.newBuilder()
                .uri(uri("/api/admin/metrics/counters"))
                .GET()
                .header("X-Session-Id", sid)
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return json.readValue(resp.body(), Map.class);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private User createUser(String username, boolean isAdmin) {
        var id = randomId();
        var hash = hasher.hash("longpassword");
        jdbc.update(
            "INSERT INTO users (id, username, password_hash, is_admin, created_at) VALUES (?, ?, ?, ?, ?)",
            id, username, hash, isAdmin ? 1 : 0, Instant.now().toString());
        return new User(id, username, hash, null, null, null, isAdmin, Instant.now());
    }

    private String loginAs(String username, String password) throws Exception {
        var resp = http.send(
            HttpRequest.newBuilder()
                .uri(uri("/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(Map.of("username", username, "password", password))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(resp.body(), Map.class);
        return (String) body.get("sessionId");
    }

    private static String randomId() {
        var b = new byte[24];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}