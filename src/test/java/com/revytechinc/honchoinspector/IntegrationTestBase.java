package com.revytechinc.honchoinspector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.revytechinc.honchoinspector.auth.AuthController;
import com.revytechinc.honchoinspector.auth.PasswordHasher;
import com.revytechinc.honchoinspector.auth.Profile;
import com.revytechinc.honchoinspector.auth.ProfileService;
import com.revytechinc.honchoinspector.controller.HonchoController;
import com.revytechinc.honchoinspector.honcho.HonchoClientFactory;
import com.revytechinc.honchoinspector.honcho.HonchoMockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Foundation for end-to-end workflow integration tests (T24–T26).
 *
 * <p>Boots the full Spring context against an in-memory SQLite database
 * and wires {@link HonchoMockConfig} so every {@code HonchoClient} call
 * returns a captured fixture instead of hitting the real Honcho API.
 * Concrete test classes extend this base, register a user, and exercise
 * the auth/profile/Honcho-proxy flows via {@link MockMvc}.
 *
 * <h2>Why {@code @MockitoBean HonchoClientFactory}</h2>
 * {@link HonchoClientFactory} is a production {@code @Component} whose
 * constructor iterates every {@code HonchoClient} bean in the context and
 * throws if two clients claim the same {@code HonchoApiVersion}. Both the
 * real {@code HonchoV3Client} (production {@code @Component}) and the
 * fixture-backed mock (from {@link HonchoMockConfig}) claim V3, so the
 * production factory's constructor would fail at boot. Replacing it with a
 * Mockito mock disables the production factory; the {@code @Primary} real
 * factory declared in {@link HonchoMockConfig} (built with only the mock
 * client) wins on autowire.
 *
 * <h2>Database isolation</h2>
 * {@link BeforeEach} deletes from all three tables so each test starts
 * with a known-empty DB. The {@code HONCHO_DB_PATH} property forces
 * in-memory SQLite (a fresh, isolated DB per JVM); the
 * {@code honcho.crypto-key} property is a fixed 32-byte base64 string so
 * profile API keys are encryptable across test runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(HonchoMockConfig.class)
@TestPropertySource(properties = {
    "HONCHO_DB_PATH=jdbc:sqlite::memory:",
    "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24="
})
public abstract class IntegrationTestBase {

    /**
     * Silences the production {@link HonchoClientFactory} bean (declared as
     * {@code @Component} in {@code com.revytechinc.honchoinspector.honcho}).
     * Its fail-fast constructor would throw because both the real
     * {@code HonchoV3Client} and the fixture-backed mock from
     * {@link HonchoMockConfig} claim {@link HonchoApiVersion#V3}. The
     * {@code @Primary} factory from {@link HonchoMockConfig} is the one
     * autowired by {@code HonchoProxyService} and other collaborators; this
     * Mockito stub replaces the production factory with a no-op so its
     * constructor never runs.
     *
     * <p>The {@code name} attribute is <strong>required</strong>: Spring's
     * default bean name for the production factory is {@code honchoClientFactory}
     * (derived from the {@code @Component} class name), and {@code @MockitoBean}
     * matches by that name — the field name alone is not enough.
     */
    @MockitoBean(name = "honchoClientFactory")
    HonchoClientFactory productionFactorySilencer;

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected HonchoClientFactory honchoFactory;
    @Autowired protected ProfileService profiles;
    @Autowired protected PasswordHasher hasher;

    private static final SecureRandom RNG = new SecureRandom();

    /**
     * Create a user directly in the DB (bypassing the now-admin-gated
     * /api/auth/register endpoint) and return the user's id. This is the
     * test-only path: production users are created by AdminBootstrap (first
     * admin) or by an authenticated admin via POST /api/admin/users.
     */
    protected String createUserDirect(String username, String password, boolean isAdmin) {
        var id = randomId();
        jdbc.update(
            "INSERT INTO users (id, username, password_hash, firstname, lastname, email, is_admin, created_at) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, username, hasher.hash(password), null, null, null,
            isAdmin ? 1 : 0, Instant.now().toString());
        return id;
    }

    private static String randomId() {
        var b = new byte[24];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM honcho_profiles");
        jdbc.update("DELETE FROM users");
    }

    /**
     * Create a user directly in the DB (since /api/auth/register is now
     * admin-gated) and log them in. Returns the {@code sessionId} hex
     * string suitable for the {@code X-Session-Id} header.
     */
    protected String registerAndLogin(String username, String password) throws Exception {
        createUserDirect(username, password, false);
        return loginAs(username, password);
    }

    /**
     * Log in as the given user. Assumes the user already exists in the DB
     * (either pre-existing or created via {@link #createUserDirect}).
     */
    protected String loginAs(String username, String password) throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AuthController.CredentialsDto(username, password))))
            .andExpect(status().isOk())
            .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("sessionId").asText();
    }

    /**
     * Create an admin user and return their session id. Convenience for
     * tests that need to exercise admin-only endpoints.
     */
    protected String adminLogin(String username, String password) throws Exception {
        createUserDirect(username, password, true);
        return loginAs(username, password);
    }

    /**
     * Look up the single registered user's id (assumes one user per test).
     * Useful when tests need to bind a profile to a specific user without
     * re-querying the auth endpoint.
     */
    protected String singleUserId(String username) {
        return jdbc.queryForObject(
            "SELECT id FROM users WHERE username = ?", String.class, username);
    }

    /**
     * Create a profile owned by the single user with the given username.
     * Defaults the Honcho-side fields to a placeholder base URL,
     * workspace id, and user name — tests that exercise Honcho proxy
     * flows only need the row to exist, since the real client is mocked.
     */
    protected String createProfileFor(String username, String label, String apiKey) {
        String userId = singleUserId(username);
        Profile p = profiles.create(
            userId, label, apiKey,
            "https://api.honcho.dev", "ws-1", "revytech");
        return p.id();
    }

    /**
     * Convenience: attach the standard {@code X-Session-Id} +
     * {@code X-Honcho-Profile-Id} header pair to any
     * {@link MockHttpServletRequestBuilder}. Saves the boilerplate at
     * every Honcho-proxy endpoint call site.
     */
    protected MockHttpServletRequestBuilder withAuth(
            MockHttpServletRequestBuilder builder, String sessionId, String profileId) {
        return builder
            .header("X-Session-Id", sessionId)
            .header(HonchoController.PROFILE_HEADER, profileId);
    }

    /** Shorthand: build a profile for the test's single user (assumed named "alice"). */
    protected String createProfile(String label, String apiKey) {
        return createProfileFor("alice", label, apiKey);
    }

    protected byte[] toJson(Object body) throws Exception {
        return json.writeValueAsBytes(body);
    }

    protected static final MediaType JSON = MediaType.APPLICATION_JSON;
}
