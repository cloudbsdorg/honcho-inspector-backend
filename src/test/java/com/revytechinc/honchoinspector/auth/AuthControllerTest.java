package com.revytechinc.honchoinspector.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class AuthControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordHasher hasher;

    @BeforeEach
    void cleanDb() {
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM honcho_profiles");
        jdbc.update("DELETE FROM users");
    }

    private String registerAndLogin(String username, String password) throws Exception {
        createUserDirect(username, password, false);
        return loginAs(username, password);
    }

    private String createUserDirect(String username, String password, boolean isAdmin) {
        var id = randomId();
        jdbc.update(
            "INSERT INTO users (id, username, password_hash, is_admin, created_at) VALUES (?, ?, ?, ?, ?)",
            id, username, hasher.hash(password), isAdmin ? 1 : 0, java.time.Instant.now().toString());
        return id;
    }

    private String loginAs(String username, String password) throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AuthController.CredentialsDto(username, password))))
            .andExpect(status().isOk())
            .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("sessionId").asText();
    }

    private String adminLogin(String username, String password) throws Exception {
        createUserDirect(username, password, true);
        return loginAs(username, password);
    }

    private static String randomId() {
        var b = new byte[24];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.HexFormat.of().formatHex(b);
    }

    @Test
    void register_returns401_when_unauthenticated() throws Exception {
        mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AuthController.CredentialsDto("alice", "passw0rd1"))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void register_returns403_when_not_admin() throws Exception {
        registerAndLogin("alice", "passw0rd1");
        mvc.perform(post("/api/auth/register")
                .header("X-Session-Id", loginAs("alice", "passw0rd1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AuthController.CredentialsDto("bob", "passw0rd2"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void register_asAdmin_createsUserWithIsAdminFalse() throws Exception {
        var adminSid = adminLogin("admin", "passw0rd1");
        mvc.perform(post("/api/auth/register")
                .header("X-Session-Id", adminSid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AuthController.CredentialsDto("bob", "passw0rd2"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.isAdmin").value(false))
            .andExpect(jsonPath("$.username").value("bob"));
    }

    @Test
    void register_returns409_when_username_taken() throws Exception {
        registerAndLogin("alice", "passw0rd1");
        var adminSid = adminLogin("admin", "passw0rd1");
        mvc.perform(post("/api/auth/register")
                .header("X-Session-Id", adminSid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AuthController.CredentialsDto("alice", "passw0rd2"))))
            .andExpect(status().isConflict());
    }

    @Test
    void register_returns400_when_password_too_short() throws Exception {
        var adminSid = adminLogin("admin", "passw0rd1");
        mvc.perform(post("/api/auth/register")
                .header("X-Session-Id", adminSid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AuthController.CredentialsDto("alice", "short"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void login_returns401_for_bad_password() throws Exception {
        registerAndLogin("alice", "passw0rd1");
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AuthController.CredentialsDto("alice", "wrongpass"))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void login_returns401_for_unknown_user() throws Exception {
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AuthController.CredentialsDto("nobody", "passw0rd1"))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void me_returns_user_when_authenticated() throws Exception {
        var sessionId = registerAndLogin("alice", "passw0rd1");
        mvc.perform(get("/api/auth/me").header("X-Session-Id", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void me_returns401_when_not_authenticated() throws Exception {
        mvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_invalidates_session() throws Exception {
        var sessionId = registerAndLogin("alice", "passw0rd1");
        mvc.perform(post("/api/auth/logout").header("X-Session-Id", sessionId))
            .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("X-Session-Id", sessionId))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void health_returns_counts_and_needs_register() throws Exception {
        mvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.needs_register").value(true))
            .andExpect(jsonPath("$.users").value(0));
        registerAndLogin("alice", "passw0rd1");
        mvc.perform(get("/api/health"))
            .andExpect(jsonPath("$.needs_register").value(false))
            .andExpect(jsonPath("$.users").value(1));
    }

    @Test
    void profiles_endpoint_requires_session() throws Exception {
        mvc.perform(get("/api/profiles"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void create_and_list_profile() throws Exception {
        var sessionId = registerAndLogin("alice", "passw0rd1");
        mvc.perform(post("/api/profiles")
                .header("X-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new ProfileController.ProfileCreateDto(
                    "production", "hnc_test_key", "https://mcp.honcho.cloudbsd.org",
                    "default", "revytech", null))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.label").value("production"))
            .andExpect(jsonPath("$.apiKeyEncrypted").isNotEmpty());
        mvc.perform(get("/api/profiles").header("X-Session-Id", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].label").value("production"));
    }

    @Test
    void honcho_endpoint_requires_profile_header() throws Exception {
        var sessionId = registerAndLogin("alice", "passw0rd1");
        mvc.perform(get("/api/peers").header("X-Session-Id", sessionId))
            .andExpect(status().isBadRequest());
    }

    @Test
    void honcho_endpoint_returns_404_when_profile_not_found() throws Exception {
        var sessionId = registerAndLogin("alice", "passw0rd1");
        mvc.perform(get("/api/peers")
                .header("X-Session-Id", sessionId)
                .header("X-Honcho-Profile-Id", "nope"))
            .andExpect(status().isNotFound());
    }
}
