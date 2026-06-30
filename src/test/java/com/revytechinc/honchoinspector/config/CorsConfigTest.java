package com.revytechinc.honchoinspector.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CorsConfig}. The CORS policy is computed per-request
 * from the inbound {@code Origin} and {@code Host} headers, so these
 * tests exercise the resolver directly with
 * {@link MockHttpServletRequest} rather than spinning up the full Spring
 * web stack.
 *
 * <p>The architectural rule under test: <b>if the {@code Origin}
 * host:port matches the {@code Host} header, the request is same-origin
 * through the relay and is always allowed, regardless of deployment
 * hostname.</b> Cross-origin requests are allowed only when they appear
 * in the {@code CORS_ALLOWED_ORIGINS} environment variable.
 */
class CorsConfigTest {

    private static final String TRUSTED = "https://trusted.com";

    @Test
    void allowsSameOriginExternalHostname() {
        var cfg = new CorsConfig("");
        var c = resolve(cfg, "https://app.example.com", "app.example.com");

        assertThat(c.getAllowedOrigins()).containsExactly("https://app.example.com");
        assertThat(c.getAllowCredentials()).isTrue();
        assertThat(c.getAllowedMethods())
            .containsExactlyInAnyOrder("GET", "POST", "PUT", "DELETE", "OPTIONS");
        assertThat(c.getExposedHeaders())
            .containsExactlyInAnyOrder("X-Session-Id", "X-Honcho-Profile-Id");
    }

    @Test
    void allowsSameOriginWithExplicitPort() {
        var cfg = new CorsConfig("");
        var c = resolve(cfg, "http://192.168.1.5:4200", "192.168.1.5:4200");

        assertThat(c.getAllowedOrigins()).containsExactly("http://192.168.1.5:4200");
        assertThat(c.getAllowCredentials()).isTrue();
    }

    @Test
    void allowsSameOriginWithDefaultPort() {
        var cfg = new CorsConfig("");
        var c = resolve(cfg, "https://example.com", "example.com");

        assertThat(c.getAllowedOrigins()).containsExactly("https://example.com");
        assertThat(c.getAllowCredentials()).isTrue();
    }

    @Test
    void allowsSameOriginLocalhost() {
        var cfg = new CorsConfig("");
        var c = resolve(cfg, "http://localhost:4200", "localhost:4200");

        assertThat(c.getAllowedOrigins()).containsExactly("http://localhost:4200");
        assertThat(c.getAllowCredentials()).isTrue();
    }

    @Test
    void allowsSameOriginCaseInsensitive() {
        var cfg = new CorsConfig("");
        var c = resolve(cfg, "https://App.Example.Com", "app.example.com");

        assertThat(c.getAllowedOrigins()).containsExactly("https://App.Example.Com");
        assertThat(c.getAllowCredentials()).isTrue();
    }

    @Test
    void passesThroughWhenOriginHeaderAbsent() {
        // No Origin header at all — curl, server-to-server, native fetchers.
        var cfg = new CorsConfig(TRUSTED);
        var c = resolve(cfg, null, "anything");

        assertThat(c).as("no Origin → no CORS check, pass through").isNull();
    }

    @Test
    void passesThroughWhenOriginHeaderBlank() {
        var cfg = new CorsConfig(TRUSTED);
        var c = resolve(cfg, "", "anything");

        assertThat(c).as("blank Origin → no CORS check, pass through").isNull();
    }

    @Test
    void rejectsCrossOriginWithoutEnvVar() {
        var cfg = new CorsConfig(""); // empty env var
        var c = resolve(cfg, "https://evil.com", "app.example.com");

        // Empty allowedOrigins means Spring's CorsFilter will respond
        // 403 to the preflight and to the actual cross-origin request.
        assertThat(c.getAllowedOrigins()).isEmpty();
    }

    @Test
    void allowsCrossOriginWhenEnvVarMatches() {
        var cfg = new CorsConfig(TRUSTED);
        var c = resolve(cfg, TRUSTED, "app.example.com");

        assertThat(c.getAllowedOrigins()).containsExactly(TRUSTED);
        assertThat(c.getAllowCredentials())
            .as("cross-origin cannot have credentials")
            .isFalse();
    }

    @Test
    void rejectsCrossOriginWhenEnvVarDoesNotMatch() {
        var cfg = new CorsConfig(TRUSTED);
        var c = resolve(cfg, "https://attacker.com", "app.example.com");

        assertThat(c.getAllowedOrigins()).isEmpty();
    }

    @Test
    void sameOriginAlwaysBeatsEnvVar() {
        // Same-origin must win even when the env var lists a totally
        // different origin. The whole point is that no env var can
        // break the same-origin rule.
        var cfg = new CorsConfig(TRUSTED);
        var c = resolve(cfg, "https://app.example.com", "app.example.com");

        assertThat(c.getAllowedOrigins()).containsExactly("https://app.example.com");
        assertThat(c.getAllowCredentials()).isTrue();
    }

    private static CorsConfiguration resolve(CorsConfig cfg, String origin, String host) {
        var req = new MockHttpServletRequest("GET", "/api/foo");
        if (origin != null) {
            req.addHeader("Origin", origin);
        }
        if (host != null) {
            req.addHeader("Host", host);
        }
        return cfg.resolve(req);
    }
}
