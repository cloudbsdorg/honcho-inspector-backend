package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SetupControllerTest extends IntegrationTestBase {

    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void wipeUsers() {
        // Force the DB back to "first run" state for every test.
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM users");
    }

    @Test
    @DisplayName("/api/health on empty DB returns first_run: true and needs_register: true")
    void health_firstRun() throws Exception {
        mvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.first_run").value(true))
            .andExpect(jsonPath("$.needs_register").value(true));
    }

    @Test
    @DisplayName("/api/health after first user returns first_run: false and needs_register: false")
    void health_afterFirstUser() throws Exception {
        createUserDirect("alice", "alicepw1234", true);
        mvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.first_run").value(false))
            .andExpect(jsonPath("$.needs_register").value(false));
    }

    @Test
    @DisplayName("POST /api/setup/first-admin: empty DB, valid payload returns 200 with sessionId + admin user")
    void firstAdmin_success() throws Exception {
        String body = """
            {"username":"alice","password":"correct horse battery staple",
             "firstname":"Alice","lastname":"Admin","email":"alice@example.com"}
            """;
        MvcResult res = mvc.perform(post("/api/setup/first-admin").contentType("application/json").content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").exists())
            .andExpect(jsonPath("$.user.username").value("alice"))
            .andExpect(jsonPath("$.user.isAdmin").value(true))
            .andReturn();
        // After the call, the DB has one user, and that user is admin.
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE is_admin = 1", Long.class);
        assertNotNull(count);
        assertEquals(1L, count);
        String response = res.getResponse().getContentAsString();
        assertTrue(response.contains("sessionId"), response);
    }

    @Test
    @DisplayName("POST /api/setup/first-admin: 409 when DB already has a user")
    void firstAdmin_dbNotEmpty_returns409() throws Exception {
        createUserDirect("seed", "seedpw12345", true);
        String body = """
            {"username":"alice","password":"correct horse battery staple"}
            """;
        mvc.perform(post("/api/setup/first-admin").contentType("application/json").content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("first admin already exists; use POST /api/auth/register (admin-only) to add more users"));
    }

    @Test
    @DisplayName("POST /api/setup/first-admin: 400 on password too short")
    void firstAdmin_shortPassword() throws Exception {
        String body = """
            {"username":"alice","password":"short"}
            """;
        mvc.perform(post("/api/setup/first-admin").contentType("application/json").content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/setup/first-admin: 400 on missing username")
    void firstAdmin_missingUsername() throws Exception {
        String body = """
            {"username":"","password":"correct horse battery staple"}
            """;
        mvc.perform(post("/api/setup/first-admin").contentType("application/json").content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/setup/first-admin: 409 on duplicate username (race between two first-run calls)")
    void firstAdmin_duplicateUsername() throws Exception {
        createUserDirect("alice", "alicepw1234", true);
        // The first-user check would already 409 before we got here, but
        // if a future race bypassed the count check, the insert would
        // throw UserExistsException. We simulate that by trying again
        // with a body whose user collides with the seeded one.
        // (This test exists to lock the conflict semantics.)
        String body = """
            {"username":"alice","password":"differentpass1"}
            """;
        mvc.perform(post("/api/setup/first-admin").contentType("application/json").content(body))
            .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /api/setup/first-admin: 401 not required (the endpoint is publicly reachable when DB is empty)")
    void firstAdmin_doesNotRequireSession() throws Exception {
        // No X-Session-Id header — the SessionAuthFilter must let this through.
        String body = """
            {"username":"alice","password":"correct horse battery staple"}
            """;
        mvc.perform(post("/api/setup/first-admin").contentType("application/json").content(body))
            .andExpect(status().isOk());
    }
}
