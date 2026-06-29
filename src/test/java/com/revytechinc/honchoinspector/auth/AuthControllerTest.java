package com.revytechinc.honchoinspector.auth;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

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
    void changeOwnPassword_happyPath_returns204_and_revokesSessions_and_canLoginWithNew() throws Exception {
        // The end-to-end contract: after a successful self-service
        // password change, the caller's old session is gone, the
        // old password no longer works, and the new password
        // works. This is the user-facing test that proves the
        // endpoint actually does what the docstring claims.
        var sessionId = registerAndLogin("alice", "old-passw0rd");
        mvc.perform(post("/api/auth/me/password")
                .header("X-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of(
                    "currentPassword", "old-passw0rd",
                    "newPassword", "new-passw0rd"))))
            .andExpect(status().isNoContent());
        // The caller's session is gone — must re-auth.
        mvc.perform(get("/api/auth/me").header("X-Session-Id", sessionId))
            .andExpect(status().isUnauthorized());
        // The old password no longer works.
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AuthController.CredentialsDto("alice", "old-passw0rd"))))
            .andExpect(status().isUnauthorized());
        // The new password works.
        var newSessionId = loginAs("alice", "new-passw0rd");
        mvc.perform(get("/api/auth/me").header("X-Session-Id", newSessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void changeOwnPassword_wrongCurrentPassword_returns401_and_doesNotChangeHash() throws Exception {
        // Wrong current password: 401 with the SAME generic message
        // as the login endpoint (don't leak whether the username
        // exists or whether the password is wrong). The hash must
        // be unchanged — the user's password is NOT reset on a
        // failed attempt.
        var sessionId = registerAndLogin("alice", "correct-passw0rd");
        mvc.perform(post("/api/auth/me/password")
                .header("X-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of(
                    "currentPassword", "wrong-passw0rd",
                    "newPassword", "new-passw0rd"))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid username or password"));
        // Session is still valid (the failed change didn't kick us out).
        mvc.perform(get("/api/auth/me").header("X-Session-Id", sessionId))
            .andExpect(status().isOk());
        // Old password still works.
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AuthController.CredentialsDto("alice", "correct-passw0rd"))))
            .andExpect(status().isOk());
    }

    @Test
    void changeOwnPassword_shortNewPassword_returns400() throws Exception {
        var sessionId = registerAndLogin("alice", "old-passw0rd");
        mvc.perform(post("/api/auth/me/password")
                .header("X-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of(
                    "currentPassword", "old-passw0rd",
                    "newPassword", "7chars!"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void changeOwnPassword_unauthenticated_returns401() throws Exception {
        // No X-Session-Id header: the SessionAuthFilter rejects
        // with 401 before the controller is reached.
        mvc.perform(post("/api/auth/me/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of(
                    "currentPassword", "x",
                    "newPassword", "new-passw0rd"))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void health_returns_needs_register_no_counts() throws Exception {
        mvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.needsRegister").value(true))
            .andExpect(jsonPath("$.users").doesNotExist())
            .andExpect(jsonPath("$.sessions").doesNotExist())
            .andExpect(jsonPath("$.profiles").doesNotExist());
        registerAndLogin("alice", "passw0rd1");
        mvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.needsRegister").value(false))
            .andExpect(jsonPath("$.users").doesNotExist());
    }

    @Test
    void health_chat_enabled_defaults_to_false() throws Exception {
        // Default behavior (no HONCHO_UI_CHAT_ENABLED override in
        // this test class): chat-enabled is false so the UI hides
        // the chat button + popout by default. Operators opt in
        // by setting HONCHO_UI_CHAT_ENABLED=true.
        mvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.chatEnabled").value(false));
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
