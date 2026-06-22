package com.revytechinc.honchoinspector.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
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
class AdminAuditControllerTest {

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
    void get_withoutSession_returns401() throws Exception {
        mvc.perform(get("/api/admin/audit"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void get_asNonAdmin_returns403() throws Exception {
        createUser("alice", false);
        var sid = login("alice", "longpassword");
        mvc.perform(get("/api/admin/audit").header("X-Session-Id", sid))
            .andExpect(status().isForbidden());
    }

    @Test
    void get_asAdmin_returns200() throws Exception {
        createUser("admin", true);
        var sid = login("admin", "longpassword");
        mvc.perform(get("/api/admin/audit").header("X-Session-Id", sid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void get_invalidSince_returns400() throws Exception {
        createUser("admin", true);
        var sid = login("admin", "longpassword");
        mvc.perform(get("/api/admin/audit?since=not-a-date")
                .header("X-Session-Id", sid))
            .andExpect(status().isBadRequest());
    }

    @Test
    void get_filterByAction_returnsMatching() throws Exception {
        var admin = createUser("admin", true);
        createUser("alice", false);
        var sid = login("admin", "longpassword");
        mvc.perform(post("/api/admin/users")
                .header("X-Session-Id", sid)
                .contentType("application/json")
                .content(json.writeValueAsBytes(Map.of(
                    "username", "bob", "password", "longpassword"))))
            .andExpect(status().isCreated());
        mvc.perform(get("/api/admin/audit?action=user.create")
                .header("X-Session-Id", sid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].action").value("user.create"));
    }

    @Test
    void get_filterByActor_returnsMatching() throws Exception {
        var admin = createUser("admin", true);
        var alice = createUser("alice", false);
        var adminSid = login("admin", "longpassword");
        mvc.perform(post("/api/admin/users")
                .header("X-Session-Id", adminSid)
                .contentType("application/json")
                .content(json.writeValueAsBytes(Map.of(
                    "username", "bob", "password", "longpassword"))))
            .andExpect(status().isCreated());
        mvc.perform(get("/api/admin/audit?actor=" + admin.id())
                .header("X-Session-Id", adminSid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].actorUserId").value(admin.id()));
    }

    @Test
    void get_filterBySince_excludesOlder() throws Exception {
        var admin = createUser("admin", true);
        var alice = createUser("alice", false);
        var sid = login("admin", "longpassword");
        jdbc.update(
            "INSERT INTO audit_log (id, actor_user_id, action, target_user_id, created_at) "
          + "VALUES (?, ?, ?, ?, ?)",
            randomId(), admin.id(), "user.create", alice.id(), Instant.now().minusSeconds(86400 * 30).toString());
        jdbc.update(
            "INSERT INTO audit_log (id, actor_user_id, action, target_user_id, created_at) "
          + "VALUES (?, ?, ?, ?, ?)",
            randomId(), admin.id(), "user.create", alice.id(), Instant.now().toString());
        mvc.perform(get("/api/admin/audit?since=" + Instant.now().minusSeconds(86400).toString())
                .header("X-Session-Id", sid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void get_pageSizeAll_returnsEverything() throws Exception {
        var admin = createUser("admin", true);
        var alice = createUser("alice", false);
        var sid = login("admin", "longpassword");
        for (int i = 0; i < 5; i++) {
            jdbc.update(
                "INSERT INTO audit_log (id, actor_user_id, action, target_user_id, created_at) "
              + "VALUES (?, ?, ?, ?, ?)",
                randomId(), admin.id(), "test.event", alice.id(), Instant.now().toString());
        }
        mvc.perform(get("/api/admin/audit?pageSize=ALL")
                .header("X-Session-Id", sid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pages").value(1))
            .andExpect(jsonPath("$.rows").value(2147483647));
    }

    @Test
    void get_metadataJsonDeserialized() throws Exception {
        var admin = createUser("admin", true);
        var alice = createUser("alice", false);
        var sid = login("admin", "longpassword");
        jdbc.update(
            "INSERT INTO audit_log (id, actor_user_id, action, target_user_id, metadata, created_at) "
          + "VALUES (?, ?, ?, ?, ?, ?)",
            randomId(), admin.id(), "user.create", alice.id(), "{\"username\":\"alice\"}", Instant.now().toString());
        mvc.perform(get("/api/admin/audit")
                .header("X-Session-Id", sid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].metadata.username").value("alice"));
    }

    private User createUser(String username, boolean isAdmin) {
        var id = randomId();
        var hash = hasher.hash("longpassword");
        jdbc.update(
            "INSERT INTO users (id, username, password_hash, is_admin, created_at) VALUES (?, ?, ?, ?, ?)",
            id, username, hash, isAdmin ? 1 : 0, Instant.now().toString());
        return new User(id, username, hash, null, null, null, isAdmin, Instant.now());
    }

    private String login(String username, String password) throws Exception {
        var result = mvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(json.writeValueAsBytes(Map.of(
                    "username", username, "password", password))))
            .andExpect(status().isOk())
            .andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).get("sessionId").asText();
    }

    private static String randomId() {
        var b = new byte[16];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
