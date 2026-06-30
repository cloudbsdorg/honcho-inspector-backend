package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the {@code honcho.ui.api-key-visible-to-non-admin} flag's
 * <strong>locked-down</strong> behavior (flag = {@code false}, the
 * presentation-mode toggle operators flip via
 * {@code HONCHO_UI_API_KEY_VISIBLE_TO_NON_ADMIN=false}).
 *
 * <p>Companion to {@link ProfileControllerApiKeyVisibilityFlagTrueTest},
 * which exercises the default flag=true behavior. Together the two
 * classes pin both branches of the toggle and prevent regressions.
 *
 * <p>Expected behavior with flag = false:
 * <ul>
 *   <li>Admin + flag=false: reveal 200, update-with-apiKey 200, test 200 (admin always wins)</li>
 *   <li>Non-admin + flag=false: reveal 403, update-with-apiKey 403, test 403 (NEW locked-down behavior)</li>
 *   <li>Non-admin + flag=false: update WITHOUT apiKey (label/baseUrl/etc.) still succeeds (other fields stay editable)</li>
 * </ul>
 */
@TestPropertySource(properties = {
    "HONCHO_DB_PATH=jdbc:sqlite::memory:",
    "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24=",
    "honcho.ui.api-key-visible-to-non-admin=false"
})
class ProfileControllerApiKeyVisibilityFlagFalseTest extends IntegrationTestBase {

    private static final String ALICE = "alice";
    private static final String ALICE_PASS = "alicepass123";
    private static final String ADMIN_USER = "adminUser";
    private static final String ADMIN_PASS = "adminpass123";
    private static final String PLAINTEXT_KEY = "super-secret-key-12345";

    @Test
    @DisplayName("admin + flag=false: reveal returns 200 (admin always wins)")
    void admin_can_reveal_when_flag_false() throws Exception {
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
    @DisplayName("admin + flag=false: update with new apiKey returns 200 (admin always wins)")
    void admin_can_update_apiKey_when_flag_false() throws Exception {
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
    @DisplayName("admin + flag=false: test returns 200 (admin always wins)")
    void admin_can_test_when_flag_false() throws Exception {
        String adminSession = adminLogin(ADMIN_USER, ADMIN_PASS);
        String profileId = createProfileFor(ADMIN_USER, "Admin-Production", PLAINTEXT_KEY);

        mvc.perform(post("/api/profiles/{id}/test", profileId)
                .header("X-Session-Id", adminSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.message").value("reachable"));
    }

    @Test
    @DisplayName("non-admin + flag=false: reveal returns 403 (NEW locked-down behavior)")
    void nonadmin_blocked_from_reveal_when_flag_false() throws Exception {
        String sessionId = registerAndLogin(ALICE, ALICE_PASS);
        String profileId = createProfileFor(ALICE, "Production", PLAINTEXT_KEY);

        mvc.perform(get("/api/profiles/{id}/reveal", profileId)
                .header("X-Session-Id", sessionId))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @DisplayName("non-admin + flag=false: update WITH new apiKey returns 403 (NEW locked-down behavior)")
    void nonadmin_blocked_from_updating_apiKey_when_flag_false() throws Exception {
        String sessionId = registerAndLogin(ALICE, ALICE_PASS);
        String profileId = createProfileFor(ALICE, "Production", PLAINTEXT_KEY);

        mvc.perform(put("/api/profiles/{id}", profileId)
                .header("X-Session-Id", sessionId)
                .contentType(JSON)
                .content(toJson(new ProfileController.ProfileUpdateDto(
                    null, "new-nonadmin-key", null, null, null, null))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @DisplayName("non-admin + flag=false: test returns 403 (NEW locked-down behavior)")
    void nonadmin_blocked_from_test_when_flag_false() throws Exception {
        String sessionId = registerAndLogin(ALICE, ALICE_PASS);
        String profileId = createProfileFor(ALICE, "Production", PLAINTEXT_KEY);

        mvc.perform(post("/api/profiles/{id}/test", profileId)
                .header("X-Session-Id", sessionId))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @DisplayName("non-admin + flag=false: update WITHOUT apiKey (label change) still succeeds — other fields stay editable")
    void nonadmin_can_update_other_fields_when_flag_false() throws Exception {
        String sessionId = registerAndLogin(ALICE, ALICE_PASS);
        String profileId = createProfileFor(ALICE, "Production", PLAINTEXT_KEY);

        mvc.perform(put("/api/profiles/{id}", profileId)
                .header("X-Session-Id", sessionId)
                .contentType(JSON)
                .content(toJson(new ProfileController.ProfileUpdateDto(
                    "Production (US-East)", null, null, null, null, null))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.label").value("Production (US-East)"))
            .andExpect(jsonPath("$.baseUrl").value("https://api.honcho.dev"))
            .andExpect(jsonPath("$.workspaceId").value("ws-1"))
            .andExpect(jsonPath("$.honchoUserName").value("revytech"));
    }
}