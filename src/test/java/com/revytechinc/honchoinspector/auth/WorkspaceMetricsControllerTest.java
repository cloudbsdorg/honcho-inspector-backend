package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.honcho.HonchoMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auth/role matrix tests for {@code GET /api/admin/metrics/counters}.
 *
 * <p>The endpoint used to live on {@code AdminMetricsController} with a
 * class-level {@code @RequireAdmin}. The dashboard at {@code /} is
 * reachable by any authenticated user, so a non-admin signing in was
 * silently 403'd the moment {@code metrics.service.load()} fired. The
 * gate was unjustified because every value the endpoint returns is a
 * workspace-level aggregate already reachable via {@code /api/peers},
 * {@code /api/sessions}, and the Honcho proxy — admin gating just
 * silently broke the dashboard's KPI strip for regular users.
 *
 * <p>This class locks the new contract:
 * <ul>
 *   <li>Anonymous (no {@code X-Session-Id}) → 401 (auth required).</li>
 *   <li>Authenticated admin → 200 with all seven counter keys.</li>
 *   <li>Authenticated non-admin (alice) → 200 with all seven counter
 *       keys — was 403, now 200.</li>
 * </ul>
 *
 * <p>Response-shape and counter-arithmetic tests live in
 * {@code AdminMetricsControllerTest}; this class is intentionally narrow
 * to the role/gating matrix that the de-gating actually changed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "HONCHO_DB_PATH=jdbc:sqlite::memory:",
    "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24="
})
class WorkspaceMetricsControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordHasher hasher;
    @Autowired HonchoMetrics metrics;
    @Autowired MeterRegistry registry;

    private static final SecureRandom RNG = new SecureRandom();

    @BeforeEach
    void cleanDb() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM honcho_profiles");
        jdbc.update("DELETE FROM users");
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
    void counters_anonymous_returns401() throws Exception {
        mvc.perform(get("/api/admin/metrics/counters"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void counters_asAuthenticatedNonAdmin_returns200AndAllCounterKeys() throws Exception {
        createUser("alice", false);

        var sid = loginAs("alice", "longpassword");
        metrics.recordSearch();
        metrics.recordPeersListed();

        mvc.perform(get("/api/admin/metrics/counters").header("X-Session-Id", sid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.counters['honcho.inspector.searches']").value(1.0))
            .andExpect(jsonPath("$.counters['honcho.inspector.peers.listed']").value(1.0))
            .andExpect(jsonPath("$.counters['honcho.inspector.dreams.scheduled']").value(0.0))
            .andExpect(jsonPath("$.counters['honcho.inspector.messages.sent']").value(0.0))
            .andExpect(jsonPath("$.counters['honcho.inspector.sessions.listed']").value(0.0))
            .andExpect(jsonPath("$.counters['honcho.inspector.profiles.tested']").value(0.0))
            .andExpect(jsonPath("$.counters['workspace.messageCount']").value(0.0))
            .andExpect(jsonPath("$.capturedAt").isNotEmpty());
    }

    @Test
    void counters_asAdmin_returns200AndAllCounterKeys() throws Exception {
        createUser("admin", true);

        var sid = loginAs("admin", "longpassword");
        metrics.recordSearch();
        metrics.recordSearch();
        metrics.recordSessionsListed();

        mvc.perform(get("/api/admin/metrics/counters").header("X-Session-Id", sid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.counters['honcho.inspector.searches']").value(2.0))
            .andExpect(jsonPath("$.counters['honcho.inspector.sessions.listed']").value(1.0))
            .andExpect(jsonPath("$.counters['honcho.inspector.dreams.scheduled']").value(0.0))
            .andExpect(jsonPath("$.counters['honcho.inspector.messages.sent']").value(0.0))
            .andExpect(jsonPath("$.counters['honcho.inspector.peers.listed']").value(0.0))
            .andExpect(jsonPath("$.counters['honcho.inspector.profiles.tested']").value(0.0))
            .andExpect(jsonPath("$.counters['workspace.messageCount']").value(0.0))
            .andExpect(jsonPath("$.capturedAt").isNotEmpty());
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
        var result = mvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(json.writeValueAsBytes(Map.of(
                    "username", username, "password", password))))
            .andExpect(status().isOk())
            .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("sessionId").asText();
    }

    private static String randomId() {
        var b = new byte[24];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
