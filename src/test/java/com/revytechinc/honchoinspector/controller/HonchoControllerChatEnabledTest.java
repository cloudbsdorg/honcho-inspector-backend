package com.revytechinc.honchoinspector.controller;

import tools.jackson.databind.ObjectMapper;
import com.revytechinc.honchoinspector.auth.AdminAudit;
import com.revytechinc.honchoinspector.auth.AuthController;
import com.revytechinc.honchoinspector.auth.PasswordHasher;
import com.revytechinc.honchoinspector.auth.ProfileService;
import com.revytechinc.honchoinspector.service.HonchoProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the {@code honcho.ui.chat-enabled} feature gate.
 *
 * <p>The companion {@link HonchoControllerTest} exercises the
 * disabled-by-default path (it inherits {@code chat-enabled=false}
 * from {@code application.yml} via {@link TestPropertySource}). This
 * class flips the gate on and asserts that requests now reach the
 * upstream call path. Together the two tests pin both branches of
 * the toggle.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "HONCHO_DB_PATH=jdbc:sqlite::memory:",
    "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24=",
    "honcho.ui.chat-enabled=true",
    // The chat/stream endpoint hits the upstream directly via
    // RestClient (bypassing HonchoProxyService). Point the profile
    // at a black-hole port and use a 1ms timeout so the test
    // fails fast at the upstream call rather than waiting for a
    // real network round-trip. The 502 BAD_GATEWAY response proves
    // the request reached the upstream layer — anything other than
    // 404 means the feature gate let it through.
    "honcho.request-timeout-ms=1"
})
class HonchoControllerChatEnabledTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProfileService profiles;
    @Autowired PasswordHasher hasher;
    @MockitoBean HonchoProxyService honchoProxy;
    @MockitoBean AdminAudit adminAudit;

    private String sessionId;
    private String profileId;

    @BeforeEach
    void cleanDb() throws Exception {
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM honcho_profiles");
        jdbc.update("DELETE FROM users");
        sessionId = registerAndLogin("alice", "passw0rd1");
        profileId = createProfile("production", "hnc_test_key");
    }

    @Test
    void peerChatStream_reaches_upstream_when_chat_enabled() throws Exception {
        // With chat-enabled=true, a chat/stream request must
        // not be short-circuited by the feature gate. The
        // controller hits the upstream directly via RestClient
        // (bypassing HonchoProxyService), so we point the test
        // profile at a black-hole port and use a 1ms timeout
        // (see the class-level @TestPropertySource). The
        // RestClient call will fail fast, the controller will
        // catch the exception, and the test will see 502
        // BAD_GATEWAY. Anything other than 404 proves the
        // feature gate let the request through.
        Map<String, Object> body = Map.of("query", "hi", "stream", true);
        mvc.perform(withHeaders(post("/api/peers/alice/chat/stream"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)))
            .andExpect(status().isBadGateway());
    }

    private String registerAndLogin(String username, String password) throws Exception {
        createUserDirect(username, password, false);
        return loginAs(username, password);
    }

    private String loginAs(String username, String password) throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AuthController.CredentialsDto(username, password))))
            .andExpect(status().isOk())
            .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("sessionId").asText();
    }

    private String createUserDirect(String username, String password, boolean isAdmin) {
        var id = randomId();
        jdbc.update(
            "INSERT INTO users (id, username, password_hash, is_admin, created_at) VALUES (?, ?, ?, ?, ?)",
            id, username, hasher.hash(password), isAdmin ? 1 : 0, java.time.Instant.now().toString());
        return id;
    }

    private static String randomId() {
        var b = new byte[24];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.HexFormat.of().formatHex(b);
    }

    private String createProfile(String label, String apiKey) {
        var p = profiles.create(
            jdbc.queryForObject("SELECT id FROM users WHERE username = 'alice'", String.class),
            label, apiKey, "https://api.honcho.dev", "ws-1", "revytech"
        );
        assertThat(p).isNotNull();
        return p.id();
    }

    private MockHttpServletRequestBuilder withHeaders(MockHttpServletRequestBuilder b) {
        return b.header("X-Session-Id", sessionId).header(HonchoController.PROFILE_HEADER, profileId);
    }
}
