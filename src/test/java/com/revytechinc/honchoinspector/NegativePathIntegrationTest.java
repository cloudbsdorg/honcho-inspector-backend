package com.revytechinc.honchoinspector;

import com.revytechinc.honchoinspector.auth.AuthController;
import com.revytechinc.honchoinspector.auth.ProfileController;
import com.revytechinc.honchoinspector.controller.HonchoController;
import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.model.HonchoContext;
import com.revytechinc.honchoinspector.service.HonchoProxyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-cutting negative-path integration test (T34).
 *
 * <p>Exercises the 401 / 400 / 404 / 409 surfaces that span all three
 * controllers ({@code AuthController}, {@code ProfileController},
 * {@code HonchoController}) using the standard {@code SessionAuthFilter}
 * + real Spring stack wired by {@link IntegrationTestBase}. The fixture-backed
 * {@code HonchoClient} from {@code HonchoMockConfig} is sufficient for tests
 * 1–12 because those paths short-circuit before the upstream call; tests 13
 * and 14 need a controllable Honcho failure, so they stub
 * {@link HonchoProxyService} to throw {@link HonchoCallException} (see
 * {@link #honchoProxy} below).
 */
class NegativePathIntegrationTest extends IntegrationTestBase {

    private static final String ALICE = "alice";
    private static final String ALICE_PASS = "alicepass123";
    private static final String BOB = "bob";
    private static final String BOB_PASS = "bobpass123";
    private static final String PLAINTEXT_KEY = "super-secret-key-12345";

    /** 48-char hex id that matches the profile id format but does not exist. */
    private static final String NONEXISTENT_PROFILE_ID = "0".repeat(48);

    /**
     * Replaces the real {@link HonchoProxyService} so tests 13 and 14 can
     * force a {@link HonchoCallException} on specific upstream calls. The
     * other 12 tests never reach the proxy through the controller
     * (they short-circuit at the auth filter, the profile-header check,
     * the cross-user ownership check, or the profile controller), so the
     * mock's default null returns for unstubbed methods are harmless.
     *
     * <p>{@code @MockitoBean} (Spring Boot 3.4+ replacement for the
     * deprecated {@code @MockBean}) matches by type — there is only one
     * {@code HonchoProxyService} bean in the context, so no {@code name}
     * attribute is needed.
     */
    @MockitoBean
    HonchoProxyService honchoProxy;

    // ------------------------------------------------------------------
    // Honcho proxy: session / profile-header / cross-user (1-3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/peers without X-Session-Id is blocked by the auth filter (401)")
    void honchoProxyWithoutSessionReturns401() throws Exception {
        mvc.perform(get("/api/peers"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Honcho proxy with session but no X-Honcho-Profile-Id returns 400 with the missing-header error")
    void honchoProxyWithoutProfileHeaderReturns400() throws Exception {
        String sessionId = registerAndLogin(ALICE, ALICE_PASS);

        mvc.perform(get("/api/peers").header("X-Session-Id", sessionId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("missing " + HonchoController.PROFILE_HEADER + " header"));
    }

    @Test
    @DisplayName("Honcho proxy with another user's profile id returns 404 (no ownership leak)")
    void honchoProxyWithOtherUsersProfileReturns404() throws Exception {
        String aliceSession = registerAndLogin(ALICE, ALICE_PASS);
        String aliceProfile = createProfileFor(ALICE, "alice-prof", PLAINTEXT_KEY);

        String bobSession = registerAndLogin(BOB, BOB_PASS);
        String bobProfile = createProfileFor(BOB, "bob-prof", PLAINTEXT_KEY);

        mvc.perform(get("/api/peers")
                .header("X-Session-Id", aliceSession)
                .header(HonchoController.PROFILE_HEADER, bobProfile))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("profile not found"));

        mvc.perform(get("/api/peers")
                .header("X-Session-Id", aliceSession)
                .header(HonchoController.PROFILE_HEADER, aliceProfile))
            .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Auth: register / login validation (4-8)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Registering an existing username returns 409")
    void registerWithExistingUsernameReturns409() throws Exception {
        mvc.perform(post("/api/auth/register")
                .contentType(JSON)
                .content(toJson(new AuthController.CredentialsDto(ALICE, ALICE_PASS))))
            .andExpect(status().isCreated());

        mvc.perform(post("/api/auth/register")
                .contentType(JSON)
                .content(toJson(new AuthController.CredentialsDto(ALICE, "differentpassword123"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @DisplayName("Registering with a 1-character password is rejected by @Size(min=8) (400)")
    void registerWithShortPasswordReturns400() throws Exception {
        mvc.perform(post("/api/auth/register")
                .contentType(JSON)
                .content(toJson(new AuthController.CredentialsDto(ALICE, "a"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Registering with an empty username is rejected by @NotBlank (400)")
    void registerWithEmptyUsernameReturns400() throws Exception {
        mvc.perform(post("/api/auth/register")
                .contentType(JSON)
                .content(toJson(new AuthController.CredentialsDto("", ALICE_PASS))))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Login with the wrong password returns 401")
    void loginWithWrongPasswordReturns401() throws Exception {
        mvc.perform(post("/api/auth/register")
                .contentType(JSON)
                .content(toJson(new AuthController.CredentialsDto(ALICE, ALICE_PASS))))
            .andExpect(status().isCreated());

        mvc.perform(post("/api/auth/login")
                .contentType(JSON)
                .content(toJson(new AuthController.CredentialsDto(ALICE, "wrong-password-12345"))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Login with a non-existent username returns 401 (no enumeration leak)")
    void loginWithNonexistentUserReturns401() throws Exception {
        mvc.perform(post("/api/auth/login")
                .contentType(JSON)
                .content(toJson(new AuthController.CredentialsDto("ghost-user-xyz", "any-password-12345"))))
            .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Profile: auth, cross-user, and not-found (9-12)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/profiles without X-Session-Id is blocked by the auth filter (401)")
    void profileCreateWithoutSessionReturns401() throws Exception {
        mvc.perform(post("/api/profiles")
                .contentType(JSON)
                .content(toJson(profileCreate("Production"))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Revealing another user's profile id returns 404")
    void profileRevealForOtherUserReturns404() throws Exception {
        String aliceSession = registerAndLogin(ALICE, ALICE_PASS);
        String aliceProfile = createProfileFor(ALICE, "alice-secret", PLAINTEXT_KEY);

        String bobSession = registerAndLogin(BOB, BOB_PASS);
        mvc.perform(get("/api/profiles/{id}/reveal", aliceProfile)
                .header("X-Session-Id", bobSession))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @DisplayName("Revealing a syntactically-valid but non-existent profile id returns 404")
    void profileRevealForNonexistentProfileReturns404() throws Exception {
        String sessionId = registerAndLogin(ALICE, ALICE_PASS);

        mvc.perform(get("/api/profiles/{id}/reveal", NONEXISTENT_PROFILE_ID)
                .header("X-Session-Id", sessionId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @DisplayName("Deleting a profile that was just deleted returns 404")
    void profileDeleteAlreadyDeletedReturns404() throws Exception {
        String sessionId = registerAndLogin(ALICE, ALICE_PASS);
        String profileId = createProfileFor(ALICE, "doomed", PLAINTEXT_KEY);

        mvc.perform(delete("/api/profiles/{id}", profileId)
                .header("X-Session-Id", sessionId))
            .andExpect(status().isNoContent());

        mvc.perform(delete("/api/profiles/{id}", profileId)
                .header("X-Session-Id", sessionId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ------------------------------------------------------------------
    // Honcho error mapping (13-14)
    //
    // The fixture-backed HonchoClient (HonchoMockConfig) returns canned
    // data for every operation regardless of input, so a "non-existent
    // session" or "non-existent peer" would normally come back 200 with
    // the fixture payload. To exercise the controller's
    // HonchoCallException -> 4xx mapping, the @MockitoBean HonchoProxyService
    // above is stubbed to throw a 404 HonchoCallException for the specific
    // methods under test. The controller's call() helper catches the
    // exception, maps status >= 500 to 502 and < 500 to the original
    // status, and renders {error, body} as the response body.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/sessions/{id}/messages against a bogus session maps Honcho 404 to 404")
    void messageAddToNonexistentSessionReturnsHonchoError() throws Exception {
        String sessionId = registerAndLogin(ALICE, ALICE_PASS);
        String profileId = createProfileFor(ALICE, "alice-prof", PLAINTEXT_KEY);

        when(honchoProxy.addMessage(any(HonchoContext.class), anyString(), any()))
            .thenThrow(new HonchoCallException(
                "session not found", 404, "{\"detail\":\"session sess_bogus not found\"}"));

        mvc.perform(withAuth(post("/api/sessions/{sessionId}/messages", "sess_bogus"), sessionId, profileId)
                .contentType(JSON)
                .content(toJson(Map.of("messages",
                    List.of(Map.of("peer_id", "fixture-peer", "content", "hi"))))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("session not found"));
    }

    @Test
    @DisplayName("GET /api/peers/{id}/card for a non-existent peer maps Honcho 404 to 404")
    void peerGetNonexistentReturnsHonchoError() throws Exception {
        String sessionId = registerAndLogin(ALICE, ALICE_PASS);
        String profileId = createProfileFor(ALICE, "alice-prof", PLAINTEXT_KEY);

        when(honchoProxy.getPeerCard(any(HonchoContext.class), anyString()))
            .thenThrow(new HonchoCallException(
                "peer not found", 404, "{\"detail\":\"peer nonexistent not found\"}"));

        mvc.perform(withAuth(get("/api/peers/{peerId}/card", "nonexistent"), sessionId, profileId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("peer not found"));
    }

    // ------------------------------------------------------------------
    // Test fixtures
    // ------------------------------------------------------------------

    private ProfileController.ProfileCreateDto profileCreate(String label) {
        return new ProfileController.ProfileCreateDto(
            label,
            PLAINTEXT_KEY,
            "https://api.honcho.dev",
            "ws-1",
            "revytech",
            null
        );
    }
}
