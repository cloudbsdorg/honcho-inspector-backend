package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthWorkflowIntegrationTest extends IntegrationTestBase {

    private static final String HEX48_PATTERN = "[a-f0-9]{48}";
    private static final String BOGUS_SESSION = "0".repeat(48);

    private static final String ADMIN = "admin";
    private static final String ADMIN_PASS = "adminpass123";
    private static final String ALICE = "alice";
    private static final String ALICE_PASS = "alicepass123";
    private static final String BOB = "bob";
    private static final String BOB_PASS = "bobpass123";

    @Test
    @DisplayName("POST /api/auth/register without a session returns 401")
    void registerWithoutSessionReturns401() throws Exception {
        mvc.perform(post("/api/auth/register")
                .contentType(JSON)
                .content(toJson(new AuthController.CredentialsDto(ALICE, ALICE_PASS))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/auth/register as a non-admin returns 403")
    void registerAsNonAdminReturns403() throws Exception {
        createUserDirect(ALICE, ALICE_PASS, false);
        String aliceSid = loginAs(ALICE, ALICE_PASS);
        mvc.perform(post("/api/auth/register")
                .header("X-Session-Id", aliceSid)
                .contentType(JSON)
                .content(toJson(new AuthController.CredentialsDto(BOB, BOB_PASS))))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin-created users are not admin by default")
    void adminCreatedUserIsNotAdmin() throws Exception {
        String adminSid = adminLogin(ADMIN, ADMIN_PASS);
        mvc.perform(post("/api/auth/register")
                .header("X-Session-Id", adminSid)
                .contentType(JSON)
                .content(toJson(new AuthController.CredentialsDto(BOB, BOB_PASS))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.username").value(BOB))
            .andExpect(jsonPath("$.isAdmin").value(false))
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("Registering a duplicate username returns 409")
    void registerDuplicateUsernameReturns409() throws Exception {
        createUserDirect(ALICE, ALICE_PASS, false);
        String adminSid = adminLogin(ADMIN, ADMIN_PASS);
        mvc.perform(post("/api/auth/register")
                .header("X-Session-Id", adminSid)
                .contentType(JSON)
                .content(toJson(new AuthController.CredentialsDto(ALICE, "differentpass"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @DisplayName("Login returns a 48-char hex session id")
    void loginReturnsSessionId() throws Exception {
        createUserDirect(ALICE, ALICE_PASS, true);

        MvcResult result = mvc.perform(post("/api/auth/login")
                .contentType(JSON)
                .content(toJson(new AuthController.CredentialsDto(ALICE, ALICE_PASS))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").isString())
            .andExpect(jsonPath("$.user.username").value(ALICE))
            .andExpect(jsonPath("$.user.isAdmin").value(true))
            .andReturn();

        String sessionId = json.readTree(result.getResponse().getContentAsString())
            .get("sessionId").asText();
        assertThat(sessionId)
            .as("sessionId must be 24 random bytes formatted as 48 lowercase hex chars")
            .matches(HEX48_PATTERN)
            .hasSize(48);
    }

    @Test
    @DisplayName("GET /api/auth/me with a valid session returns the current user")
    void meWithValidSessionReturnsUser() throws Exception {
        createUserDirect(ALICE, ALICE_PASS, true);
        String sessionId = loginAs(ALICE, ALICE_PASS);

        mvc.perform(get("/api/auth/me").header("X-Session-Id", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value(ALICE))
            .andExpect(jsonPath("$.isAdmin").value(true))
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.createdAt").isNotEmpty());

        String meId = json.readTree(mvc.perform(get("/api/auth/me").header("X-Session-Id", sessionId))
                .andReturn().getResponse().getContentAsString())
            .get("id").asText();
        assertThat(meId).isEqualTo(singleUserId(ALICE));
    }

    @Test
    @DisplayName("GET /api/auth/me without a session header returns 401")
    void meWithoutSessionReturns401() throws Exception {
        mvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/auth/me with a syntactically valid but unknown session returns 401")
    void meWithInvalidSessionReturns401() throws Exception {
        mvc.perform(get("/api/auth/me").header("X-Session-Id", BOGUS_SESSION))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Logout invalidates the session — subsequent /me returns 401")
    void logoutInvalidatesSession() throws Exception {
        String sessionId = registerAndLogin(ALICE, ALICE_PASS);

        mvc.perform(post("/api/auth/logout").header("X-Session-Id", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        mvc.perform(get("/api/auth/me").header("X-Session-Id", sessionId))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Registering with a password shorter than 8 chars returns 400")
    void registerValidatesPasswordLength() throws Exception {
        String adminSid = adminLogin(ADMIN, ADMIN_PASS);
        mvc.perform(post("/api/auth/register")
                .header("X-Session-Id", adminSid)
                .contentType(JSON)
                .content(toJson(new AuthController.CredentialsDto(ALICE, "short"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Registering with an empty username returns 400")
    void registerValidatesUsernameLength() throws Exception {
        String adminSid = adminLogin(ADMIN, ADMIN_PASS);
        mvc.perform(post("/api/auth/register")
                .header("X-Session-Id", adminSid)
                .contentType(JSON)
                .content(toJson(new AuthController.CredentialsDto("", ALICE_PASS))))
            .andExpect(status().isBadRequest());
    }
}
