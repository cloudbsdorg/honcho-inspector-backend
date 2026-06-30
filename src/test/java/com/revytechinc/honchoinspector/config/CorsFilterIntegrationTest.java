package com.revytechinc.honchoinspector.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test that proves the {@link CorsConfig} {@code CorsFilter}
 * is actually wired into the servlet filter chain. The pure
 * {@code CorsConfigTest} unit tests exercise {@link CorsConfig#resolve}
 * in isolation and were passing while the {@code @Bean CorsConfigurationSource}
 * was ignored by {@code WebMvcAutoConfiguration} — this test boots the
 * real Spring context + servlet container so the wiring itself is under
 * test.
 *
 * <p>Each test asserts the on-the-wire behavior a real preflight
 * would observe: 403 for rejected cross-origin, 200 +
 * {@code Access-Control-Allow-Origin}/{@code -Credentials} for
 * accepted requests (same-origin through the relay, or origin on the
 * operator-supplied allowlist), and pass-through for non-browser
 * callers.
 *
 * <h2>Why MockMvc, not a real network socket</h2>
 * MockMvc with {@code @AutoConfigureMockMvc} drives the embedded
 * servlet container's filter chain (the same one {@code java -jar}
 * builds), with the only difference being that requests are dispatched
 * in-process rather than over TCP. This exercises the
 * {@code FilterRegistrationBean<CorsFilter>} registration exactly the
 * way the real JAR does; if the filter is registered with the wrong
 * URL pattern, missing order, or replaced by another bean,
 * these tests fail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class CorsFilterIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void preflightCrossOriginRejected() throws Exception {
        // Origin host != Host header AND not on CORS_ALLOWED_ORIGINS
        // (env var unset in this test context) — CorsFilter must 403.
        mvc.perform(options("/api/auth/login")
                .header("Origin", "https://evil.com")
                .header("Host", "app.example.com")
                .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isForbidden());
    }

    @Test
    void preflightSameOriginAllowed() throws Exception {
        // Origin host:port == Host header → same-origin through the
        // relay. CorsFilter must respond 200 with CORS headers and
        // the downstream handler is never invoked (preflight
        // short-circuits).
        mvc.perform(options("/api/auth/login")
                .header("Origin", "https://app.example.com")
                .header("Host", "app.example.com")
                .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.com"))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void getSameOriginAllowed() throws Exception {
        // Plain GET with matching Origin/Host — CorsFilter attaches
        // the response headers and lets the request pass through to
        // the controller.
        mvc.perform(get("/api/health")
                .header("Origin", "https://app.example.com")
                .header("Host", "app.example.com"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.com"))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void getWithoutOriginPassesThrough() throws Exception {
        // No Origin header — non-browser caller (curl, server-to-server,
        // native fetcher). CorsFilter must skip CORS processing entirely
        // and the controller must respond normally with no CORS headers.
        mvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    /**
     * Cross-origin behaviour is driven entirely by the
     * {@code CORS_ALLOWED_ORIGINS} env var. The outer class has none
     * set, so the {@code preflightCrossOriginRejected} test above
     * covers the "no env var, cross-origin → 403" path. This nested
     * class sets the env var to exercise "env var set, cross-origin
     * on the allowlist → 200" and "env var set, cross-origin not on
     * allowlist → 403" without requiring a system-property swap
     * mid-suite. Each nested class triggers a fresh
     * {@code ApplicationContext} via Spring's
     * {@code @TestPropertySource} inheritance rules.
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "CORS_ALLOWED_ORIGINS=https://trusted.com")
    class WithAllowedOrigins {

        @Autowired
        MockMvc mvc;

        @Test
        void preflightWithAllowedEnvVar() throws Exception {
            // Origin is on the CORS_ALLOWED_ORIGINS allowlist. The
            // downstream path does not need to exist (/api/foo is a
            // 404 candidate) because the preflight short-circuits at
            // the filter and never reaches the dispatcher — the
            // 200 + CORS headers ARE the entire response.
            mvc.perform(options("/api/foo")
                    .header("Origin", "https://trusted.com")
                    .header("Host", "app.example.com")
                    .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://trusted.com"));
        }

        @Test
        void preflightWithoutEnvVarRejected() throws Exception {
            // Origin is NOT on the allowlist even though the env var
            // is set; CorsFilter must 403 because the resolvable
            // config has an empty allowlist for this origin.
            mvc.perform(options("/api/foo")
                    .header("Origin", "https://not-listed.com")
                    .header("Host", "app.example.com")
                    .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
        }
    }
}
