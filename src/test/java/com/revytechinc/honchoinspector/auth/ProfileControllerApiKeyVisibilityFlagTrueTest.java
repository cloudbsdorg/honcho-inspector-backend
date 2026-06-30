package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the {@code honcho.ui.api-key-visible-to-non-admin} flag's
 * <strong>default</strong> behavior (flag = {@code true}).
 *
 * <p>Companion to
 * {@link ProfileControllerApiKeyVisibilityFlagFalseTest}, which overrides
 * the flag to {@code false} via {@link org.springframework.test.context.TestPropertySource}.
 * Together the two classes pin both branches of the toggle.
 *
 * <p>Expected behavior with flag = true (the current shipped behavior
 * that operators must not see change unless they explicitly opt in to
 * presentation mode):
 * <ul>
 *   <li>Admin + flag=true: reveal 200, update-with-apiKey 200, test 200 (control)</li>
 *   <li>Non-admin + flag=true: reveal 200, update-with-apiKey 200, test 200 (current behavior preserved)</li>
 * </ul>
 */
class ProfileControllerApiKeyVisibilityFlagTrueTest extends IntegrationTestBase {

    private static final String ALICE = "alice";
    private static final String ALICE_PASS = "alicepass123";
    private static final String ADMIN_USER = "adminUser";
    private static final String ADMIN_PASS = "adminpass123";
    private static final String PLAINTEXT_KEY = "super-secret-key-12345";

    @Test
    @DisplayName("admin + flag=true: reveal returns 200 with the plaintext key (control)")
    void admin_can_reveal_when_flag_true() throws Exception {
        // Admin must own the profile — ProfileService.getWithKey filters by current user id.
        String adminSession = adminLogin(ADMIN_USER, ADMIN_PASS);
        String profileId = createProfileFor(ADMIN_USER, "Admin-Production", PLAINTEXT_KEY);

        mvc.perform(get("/api/profiles/{id}/reveal", profileId)
                .header("X-Session-Id", adminSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profile.id").value(profileId))
            .andExpect(jsonPath("$.apiKey").value(PLAINTEXT_KEY));
    }

    @Test
    @DisplayName("admin + flag=true: update with new apiKey returns 200 (control)")
    void admin_can_update_apiKey_when_flag_true() throws Exception {
        String adminSession = adminLogin(ADMIN_USER, ADMIN_PASS);
        String profileId = createProfileFor(ADMIN_USER, "Admin-Production", PLAINTEXT_KEY);

        mvc.perform(put("/api/profiles/{id}", profileId)
                .header("X-Session-Id", adminSession)
                .contentType(JSON)
                .content(toJson(new ProfileController.ProfileUpdateDto(
                    null, "new-admin-key", null, null, null, null))))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("admin + flag=true: test returns 200 (control)")
    void admin_can_test_when_flag_true() throws Exception {
        String adminSession = adminLogin(ADMIN_USER, ADMIN_PASS);
        String profileId = createProfileFor(ADMIN_USER, "Admin-Production", PLAINTEXT_KEY);

        mvc.perform(post("/api/profiles/{id}/test", profileId)
                .header("X-Session-Id", adminSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.message").value("reachable"));
    }

    @Test
    @DisplayName("non-admin + flag=true: reveal returns 200 (current behavior preserved)")
    void nonadmin_can_reveal_when_flag_true() throws Exception {
        String sessionId = registerAndLogin(ALICE, ALICE_PASS);
        String profileId = createProfileFor(ALICE, "Production", PLAINTEXT_KEY);

        mvc.perform(get("/api/profiles/{id}/reveal", profileId)
                .header("X-Session-Id", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profile.id").value(profileId))
            .andExpect(jsonPath("$.apiKey").value(PLAINTEXT_KEY));
    }

    @Test
    @DisplayName("non-admin + flag=true: update with new apiKey returns 200 (current behavior preserved)")
    void nonadmin_can_update_apiKey_when_flag_true() throws Exception {
        String sessionId = registerAndLogin(ALICE, ALICE_PASS);
        String profileId = createProfileFor(ALICE, "Production", PLAINTEXT_KEY);

        mvc.perform(put("/api/profiles/{id}", profileId)
                .header("X-Session-Id", sessionId)
                .contentType(JSON)
                .content(toJson(new ProfileController.ProfileUpdateDto(
                    null, "new-nonadmin-key", null, null, null, null))))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("non-admin + flag=true: test returns 200 (current behavior preserved)")
    void nonadmin_can_test_when_flag_true() throws Exception {
        String sessionId = registerAndLogin(ALICE, ALICE_PASS);
        String profileId = createProfileFor(ALICE, "Production", PLAINTEXT_KEY);

        mvc.perform(post("/api/profiles/{id}/test", profileId)
                .header("X-Session-Id", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.message").value("reachable"));
    }
}