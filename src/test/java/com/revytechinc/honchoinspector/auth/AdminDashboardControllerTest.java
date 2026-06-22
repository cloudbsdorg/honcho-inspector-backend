package com.revytechinc.honchoinspector.auth;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class AdminDashboardControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordHasher hasher;

    private static final SecureRandom RNG = new SecureRandom();

    @BeforeEach
    void cleanDb() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM honcho_profiles");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void overview_withoutSession_returns401() throws Exception {
        mvc.perform(get("/api/admin/dashboard/overview"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void overview_asNonAdmin_returns403() throws Exception {
        createUser("alice", false);
        var sid = loginAs("alice", "longpassword");
        mvc.perform(get("/api/admin/dashboard/overview").header("X-Session-Id", sid))
            .andExpect(status().isForbidden());
    }

    @Test
    void overview_asAdmin_returns200() throws Exception {
        createUser("admin", true);
        createUser("alice", false);
        var sid = loginAs("admin", "longpassword");
        mvc.perform(get("/api/admin/dashboard/overview").header("X-Session-Id", sid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.usersTotal").value(2))
            .andExpect(jsonPath("$.usersAdmins").value(1))
            .andExpect(jsonPath("$.profilesTotal").value(0));
    }

    @Test
    void userDrilldown_unknown_returns404() throws Exception {
        createUser("admin", true);
        var sid = loginAs("admin", "longpassword");
        mvc.perform(get("/api/admin/dashboard/users/nonexistent").header("X-Session-Id", sid))
            .andExpect(status().isNotFound());
    }

    @Test
    void userDrilldown_known_returns200() throws Exception {
        var admin = createUser("admin", true);
        var alice = createUser("alice", false);
        var sid = loginAs("admin", "longpassword");
        mvc.perform(get("/api/admin/dashboard/users/" + alice.id()).header("X-Session-Id", sid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.username").value("alice"));
    }

    @Test
    void honchoList_returnsEmptyArrayWhenNoProfiles() throws Exception {
        createUser("admin", true);
        var sid = loginAs("admin", "longpassword");
        mvc.perform(get("/api/admin/dashboard/honcho").header("X-Session-Id", sid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profiles").isArray())
            .andExpect(jsonPath("$.reachable").value(0));
    }

    @Test
    void honchoDrilldown_unknownProfile_returns404() throws Exception {
        createUser("admin", true);
        var sid = loginAs("admin", "longpassword");
        mvc.perform(get("/api/admin/dashboard/honcho/nonexistent").header("X-Session-Id", sid))
            .andExpect(status().isNotFound());
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
