package com.revytechinc.honchoinspector.auth;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AdminDiagnosticsController}. The relay polls the
 * backend's own /actuator endpoints over loopback, so these tests
 * exercise the real Spring Boot Actuator surface (no mocking). We
 * assert the SHAPE — not the actuator's specific output — because the
 * point of this endpoint is to insulate the frontend from actuator's
 * terminology.
 *
 * Uses WebEnvironment.RANDOM_PORT + java.net.http.HttpClient (real
 * HTTP) so the embedded server actually binds a port the relay can
 * dial. Spring Boot 4 removed TestRestTemplate in favor of the
 * standard JDK HttpClient, which works fine here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "HONCHO_DB_PATH=jdbc:sqlite::memory:",
    "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24="
})
class AdminDiagnosticsControllerTest {

    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordHasher hasher;
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
    }

    @Test
    void diagnostics_withoutSession_returns401() throws Exception {
        var resp = http.send(
            HttpRequest.newBuilder()
                .uri(uri("/api/admin/diagnostics"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    void diagnostics_asNonAdmin_returns403() throws Exception {
        createUser("alice", false);
        var sid = loginAs("alice", "longpassword");
        var resp = http.send(
            HttpRequest.newBuilder()
                .uri(uri("/api/admin/diagnostics"))
                .GET()
                .header("X-Session-Id", sid)
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(403);
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnostics_asAdmin_returnsEnvelopeWithHealthBuildAndMetrics() throws Exception {
        createUser("admin", true);
        var sid = loginAs("admin", "longpassword");
        var body = fetchDiagnostics(sid);
        assertThat(body).containsKey("health");
        assertThat(body).containsKey("build");
        assertThat(body).containsKey("metrics");
        Map<String, Object> health = (Map<String, Object>) body.get("health");
        assertThat(health.get("status")).isEqualTo("UP");
        Map<String, Object> build = (Map<String, Object>) body.get("build");
        // Each key may or may not be present depending on whether the
        // git-info jar is on the classpath and whether a given metric
        // has a unit / description. We just assert the keys we DO see
        // are the right shape.
        for (var entry : build.entrySet()) {
            Object v = entry.getValue();
            if (v != null) {
                assertThat(v).isInstanceOf(String.class);
            }
        }
        var metrics = (List<Object>) body.get("metrics");
        assertThat(metrics).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnostics_metricShape_isGeneric() throws Exception {
        createUser("admin", true);
        var sid = loginAs("admin", "longpassword");
        var body = fetchDiagnostics(sid);
        var metrics = (List<Map<String, Object>>) body.get("metrics");
        for (var m : metrics) {
            assertThat(m).containsKey("name");
            assertThat(m).containsKey("measurements");
            // baseUnit and description are optional in Spring Boot
            // Actuator (omitted when null). The keys may or may not be
            // present in the JSON. We just assert the keys we DO see
            // are the right shape.
            for (var entry : m.entrySet()) {
                if (entry.getValue() != null) {
                    assertThat(entry.getValue()).isInstanceOfAny(
                        String.class, Number.class, Boolean.class, List.class, Map.class);
                }
            }
            var measurements = (List<Map<String, Object>>) m.get("measurements");
            for (var meas : measurements) {
                assertThat(meas).containsKey("statistic");
                assertThat(meas).containsKey("value");
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnostics_healthGroups_includeLivenessAndReadiness() throws Exception {
        createUser("admin", true);
        var sid = loginAs("admin", "longpassword");
        var body = fetchDiagnostics(sid);
        Map<String, Object> health = (Map<String, Object>) body.get("health");
        var groups = (List<String>) health.get("groups");
        assertThat(groups).contains("liveness", "readiness");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchDiagnostics(String sid) throws Exception {
        var resp = http.send(
            HttpRequest.newBuilder()
                .uri(uri("/api/admin/diagnostics"))
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
