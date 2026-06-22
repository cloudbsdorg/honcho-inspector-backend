package com.revytechinc.honchoinspector.auth;

import tools.jackson.databind.ObjectMapper;
import com.revytechinc.honchoinspector.filter.SessionAuthFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "HONCHO_DB_PATH=jdbc:sqlite::memory:",
    "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24="
})
class AdminUserControllerTest {

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
    void list_withoutSession_returns401() throws Exception {
        mvc.perform(get("/api/admin/users"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void list_asNonAdmin_returns403() throws Exception {
        var user = createUser("alice", false);
        var sid = login("alice", "longpassword");
        mvc.perform(get("/api/admin/users")
                .header("X-Session-Id", sid))
            .andExpect(status().isForbidden());
    }

    @Test
    void list_asAdmin_returns200() throws Exception {
        var admin = createUser("admin", true);
        createUser("alice", false);
        var sid = login("admin", "longpassword");
        mvc.perform(get("/api/admin/users")
                .header("X-Session-Id", sid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist())
            .andExpect(jsonPath("$.items[1].passwordHash").doesNotExist());
    }

    @Test
    void list_pageSizeAll_works() throws Exception {
        createUser("admin", true);
        createUser("alice", false);
        var sid = login("admin", "longpassword");
        mvc.perform(get("/api/admin/users?pageSize=ALL")
                .header("X-Session-Id", sid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pages").value(1));
    }

    @Test
    void search_byEmail_findsMatch() throws Exception {
        var admin = createUser("admin", true);
        jdbc.update("UPDATE users SET email = ? WHERE id = ?", "alice@example.com", createUser("alice", false).id());
        var sid = login("admin", "longpassword");
        mvc.perform(get("/api/admin/users/search?q=alice@example")
                .header("X-Session-Id", sid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].username").value("alice"))
            .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());
    }

    @Test
    void get_asAdmin_returns200() throws Exception {
        createUser("admin", true);
        var alice = createUser("alice", false);
        var sid = login("admin", "longpassword");
        mvc.perform(get("/api/admin/users/" + alice.id())
                .header("X-Session-Id", sid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void get_notFound_returns404() throws Exception {
        createUser("admin", true);
        var sid = login("admin", "longpassword");
        mvc.perform(get("/api/admin/users/does-not-exist")
                .header("X-Session-Id", sid))
            .andExpect(status().isNotFound());
    }

    @Test
    void create_asAdmin_returns201() throws Exception {
        createUser("admin", true);
        var sid = login("admin", "longpassword");
        mvc.perform(post("/api/admin/users")
                .header("X-Session-Id", sid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of(
                    "username", "bob",
                    "password", "longpassword",
                    "firstname", "Bob",
                    "lastname", "Jones",
                    "email", "bob@example.com",
                    "isAdmin", false))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.username").value("bob"))
            .andExpect(jsonPath("$.firstname").value("Bob"))
            .andExpect(jsonPath("$.isAdmin").value(false));
    }

    @Test
    void create_duplicateUsername_returns409() throws Exception {
        createUser("admin", true);
        createUser("alice", false);
        var sid = login("admin", "longpassword");
        mvc.perform(post("/api/admin/users")
                .header("X-Session-Id", sid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of(
                    "username", "alice",
                    "password", "longpassword"))))
            .andExpect(status().isConflict());
    }

    @Test
    void create_shortPassword_returns400() throws Exception {
        createUser("admin", true);
        var sid = login("admin", "longpassword");
        mvc.perform(post("/api/admin/users")
                .header("X-Session-Id", sid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of(
                    "username", "bob",
                    "password", "short"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void update_promoteToAdmin_returns200() throws Exception {
        createUser("admin", true);
        var alice = createUser("alice", false);
        var sid = login("admin", "longpassword");
        mvc.perform(put("/api/admin/users/" + alice.id())
                .header("X-Session-Id", sid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("isAdmin", true))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.isAdmin").value(true));
    }

    @Test
    void update_demoteLastAdmin_returns409() throws Exception {
        var admin = createUser("admin", true);
        var sid = login("admin", "longpassword");
        mvc.perform(put("/api/admin/users/" + admin.id())
                .header("X-Session-Id", sid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("isAdmin", false))))
            .andExpect(status().isConflict());
    }

    @Test
    void update_selfDemote_returns409() throws Exception {
        var admin1 = createUser("admin1", true);
        var admin2 = createUser("admin2", true);
        var sid = login("admin1", "longpassword");
        mvc.perform(put("/api/admin/users/" + admin1.id())
                .header("X-Session-Id", sid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("isAdmin", false))))
            .andExpect(status().isConflict());
    }

    @Test
    void delete_otherUser_returns204() throws Exception {
        createUser("admin", true);
        var alice = createUser("alice", false);
        var sid = login("admin", "longpassword");
        mvc.perform(delete("/api/admin/users/" + alice.id())
                .header("X-Session-Id", sid))
            .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isEqualTo(1L);
    }

    @Test
    void delete_lastAdmin_returns409() throws Exception {
        var admin = createUser("admin", true);
        var sid = login("admin", "longpassword");
        mvc.perform(delete("/api/admin/users/" + admin.id())
                .header("X-Session-Id", sid))
            .andExpect(status().isConflict());
    }

    @Test
    void delete_self_returns409() throws Exception {
        var admin = createUser("admin", true);
        createUser("alice", false);
        var sid = login("admin", "longpassword");
        mvc.perform(delete("/api/admin/users/" + admin.id())
                .header("X-Session-Id", sid))
            .andExpect(status().isConflict());
    }

    @Test
    void sessions_returnsList() throws Exception {
        createUser("admin", true);
        var alice = createUser("alice", false);
        var aliceSid = login("alice", "longpassword");
        var adminSid = login("admin", "longpassword");
        mvc.perform(get("/api/admin/users/" + alice.id() + "/sessions")
                .header("X-Session-Id", adminSid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    void revokeSessions_returnsCount() throws Exception {
        createUser("admin", true);
        var alice = createUser("alice", false);
        login("alice", "longpassword");
        var adminSid = login("admin", "longpassword");
        mvc.perform(post("/api/admin/users/" + alice.id() + "/sessions/revoke")
                .header("X-Session-Id", adminSid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.revoked").value(1));
    }

    @Test
    void resetPassword_returns204_andForcesReauth() throws Exception {
        createUser("admin", true);
        var alice = createUser("alice", false);
        var aliceSid = login("alice", "longpassword");
        var adminSid = login("admin", "longpassword");
        mvc.perform(post("/api/admin/users/" + alice.id() + "/password")
                .header("X-Session-Id", adminSid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("newPassword", "newlongpassword"))))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("X-Session-Id", aliceSid))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void resetPassword_shortPassword_returns400() throws Exception {
        createUser("admin", true);
        var alice = createUser("alice", false);
        var adminSid = login("admin", "longpassword");
        mvc.perform(post("/api/admin/users/" + alice.id() + "/password")
                .header("X-Session-Id", adminSid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("newPassword", "short"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void everyAuditEntryWritten() throws Exception {
        createUser("admin", true);
        var alice = createUser("alice", false);
        var adminSid = login("admin", "longpassword");
        mvc.perform(put("/api/admin/users/" + alice.id())
                .header("X-Session-Id", adminSid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("firstname", "Alicia"))))
            .andExpect(status().isOk());
        Long auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'user.update'", Long.class);
        assertThat(auditCount).isEqualTo(1L);
    }

    private User createUser(String username, boolean isAdmin) {
        var id = randomId();
        var hash = hasher.hash("longpassword");
        jdbc.update(
            "INSERT INTO users (id, username, password_hash, is_admin, created_at) VALUES (?, ?, ?, ?, ?)",
            id, username, hash, isAdmin ? 1 : 0, java.time.Instant.now().toString());
        return new User(id, username, hash, null, null, null, isAdmin, java.time.Instant.now());
    }

    private String login(String username, String password) {
        try {
            var result = mvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(Map.of(
                        "username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
            var body = json.readTree(result.getResponse().getContentAsByteArray());
            return body.get("sessionId").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String randomId() {
        var b = new byte[24];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static org.assertj.core.api.AbstractAssert<?, ?> assertThat(Object actual) {
        return org.assertj.core.api.Assertions.assertThat(actual);
    }
}
