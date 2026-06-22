package com.revytechinc.honchoinspector.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.revytechinc.honchoinspector.config.HonchoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "HONCHO_DB_PATH=jdbc:sqlite::memory:",
    "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24="
})
class AdminMaintenanceControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordHasher hasher;
    @Autowired HonchoProperties properties;

    private static final SecureRandom RNG = new SecureRandom();

    @BeforeEach
    void cleanDb() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM honcho_profiles");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void status_withoutSession_returns401() throws Exception {
        mvc.perform(get("/api/admin/maintenance/status"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void status_asNonAdmin_returns403() throws Exception {
        createUser("alice", false);
        var sid = loginAs("alice", "longpassword");
        mvc.perform(get("/api/admin/maintenance/status").header("X-Session-Id", sid))
            .andExpect(status().isForbidden());
    }

    @Test
    void status_asAdmin_returns200WithConfig() throws Exception {
        createUser("admin", true);
        var sid = loginAs("admin", "longpassword");
        mvc.perform(get("/api/admin/maintenance/status").header("X-Session-Id", sid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.auditRows").value(0))
            .andExpect(jsonPath("$.auditRetentionDays").value(90))
            .andExpect(jsonPath("$.auditMaxRows").value(1000000))
            .andExpect(jsonPath("$.auditPurgeCron").value("0 0 3 * * *"));
    }

    @Test
    void auditPurge_asAdmin_returnsCounts() throws Exception {
        var admin = createUser("admin", true);
        jdbc.update(
            "INSERT INTO audit_log (id, actor_user_id, action, target_user_id, created_at) "
          + "VALUES (?, ?, ?, ?, ?)",
            randomId(), admin.id(), "test.event", admin.id(),
            Instant.now().minusSeconds(86400 * 365).toString());

        var sid = loginAs("admin", "longpassword");
        mvc.perform(post("/api/admin/maintenance/audit/purge").header("X-Session-Id", sid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ageDeleted").value(1))
            .andExpect(jsonPath("$.totalDeleted").value(1));
    }

    @Test
    void auditPurge_auditsItself() throws Exception {
        var admin = createUser("admin", true);
        var sid = loginAs("admin", "longpassword");
        mvc.perform(post("/api/admin/maintenance/audit/purge").header("X-Session-Id", sid))
            .andExpect(status().isOk());
        Long purgeCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'audit.purge'", Long.class);
        assertThat(purgeCount).isEqualTo(0L);
    }

    @Test
    void sessionsPurgeExpired_returnsCount() throws Exception {
        var admin = createUser("admin", true);
        jdbc.update(
            "INSERT INTO auth_sessions (id, user_id, created_at, last_seen_at, expires_at) "
          + "VALUES (?, ?, ?, ?, ?)",
            randomId(), admin.id(),
            Instant.now().minusSeconds(86400).toString(),
            Instant.now().minusSeconds(86400).toString(),
            Instant.now().minusSeconds(3600).toString());
        jdbc.update(
            "INSERT INTO auth_sessions (id, user_id, created_at, last_seen_at, expires_at) "
          + "VALUES (?, ?, ?, ?, ?)",
            randomId(), admin.id(),
            Instant.now().toString(),
            Instant.now().toString(),
            Instant.now().plusSeconds(86400).toString());

        var sid = loginAs("admin", "longpassword");
        mvc.perform(post("/api/admin/maintenance/sessions/purge-expired")
                .header("X-Session-Id", sid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deleted").value(1));
    }

    @Test
    void sessionsPurgeExpired_auditsItself() throws Exception {
        createUser("admin", true);
        var sid = loginAs("admin", "longpassword");
        mvc.perform(post("/api/admin/maintenance/sessions/purge-expired")
                .header("X-Session-Id", sid))
            .andExpect(status().isOk());
        Long purgeCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'sessions.purge'", Long.class);
        assertThat(purgeCount).isEqualTo(1L);
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

    private static org.assertj.core.api.AbstractLongAssert<?> assertThat(Long actual) {
        return org.assertj.core.api.Assertions.assertThat(actual);
    }
}
