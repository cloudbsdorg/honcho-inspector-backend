package com.revytechinc.honchoinspector.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * CORS configuration that treats same-origin requests through the dev/prod
 * relay as trusted by default, while cross-origin requests require an
 * explicit entry in {@code CORS_ALLOWED_ORIGINS}.
 *
 * <p>The frontend sits behind an HTTP relay that forwards browser requests
 * to the backend. Once the relay preserves the browser's {@code Host}
 * header (no rewrite to loopback), any request whose {@code Origin}
 * host:port matches its {@code Host} header is functionally same-origin
 * from the user's perspective. This config grants those requests
 * unconditional access without hardcoding any hostname — the host comes
 * from the request itself, never from configuration. That keeps the
 * server deployable on operator-chosen hostnames with no source changes.
 *
 * <p>Cross-origin requests (mismatched hosts) must appear in the
 * {@code CORS_ALLOWED_ORIGINS} environment variable, a comma-separated
 * list. No env var → no cross-origin access. Loopback-only binding plus
 * the {@code SessionAuthFilter} remain the primary security boundary;
 * CORS is defense in depth.
 *
 * <h2>Wiring</h2>
 * Exposes {@link CorsFilter} via a {@link FilterRegistrationBean} bound
 * to the {@code /api/*} URL pattern and ordered at
 * {@link Ordered#HIGHEST_PRECEDENCE}. Spring Boot 4.1's
 * {@code WebMvcAutoConfiguration} does <strong>not</strong> auto-wire a
 * {@link CorsConfigurationSource} bean into
 * {@code RequestMappingHandlerMapping} for explicit
 * {@code @Bean CorsConfigurationSource} definitions (it does so for
 * {@code WebMvcConfigurer.addCorsMappings(CorsRegistry)}, but that path
 * hardcodes allowed origins and cannot use {@code Host}-equality). An
 * explicit servlet filter registration is required so preflight
 * requests are handled by our resolver rather than Spring MVC's default
 * {@code OPTIONS} handler.
 */
@Configuration
public class CorsConfig {

    private static final List<String> STANDARD_METHODS =
        List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");

    private static final List<String> EXPOSED_HEADERS =
        List.of("X-Session-Id", "X-Honcho-Profile-Id");

    private static final List<String> ALL_HEADERS = List.of("*");

    private final List<String> envAllowedOrigins;

    public CorsConfig(
        @Value("${CORS_ALLOWED_ORIGINS:}") String envAllowedOriginsCsv
    ) {
        this.envAllowedOrigins = Arrays.stream(envAllowedOriginsCsv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    /**
     * Registers a {@link CorsFilter} wrapped around
     * {@link #resolve(HttpServletRequest)} on the {@code /api/*} URL
     * pattern at {@link Ordered#HIGHEST_PRECEDENCE} so preflight
     * requests are rejected (403) or accepted (200 + CORS headers)
     * before any other servlet filter in the chain runs — in
     * particular, before {@code SessionAuthFilter} would otherwise
     * reject them for missing auth.
     *
     * <p>Why a {@link FilterRegistrationBean} rather than a
     * {@code @Component CorsFilter} bean or a
     * {@code @Bean CorsConfigurationSource}: Spring Boot 4.1's
     * {@code WebMvcAutoConfiguration} does not auto-wire an
     * application-defined {@code CorsConfigurationSource} bean into
     * {@code RequestMappingHandlerMapping}, so the bean ends up
     * registered but inert. A {@code WebMvcConfigurer} with
     * {@code addCorsMappings} does get wired, but it cannot express
     * "same-origin iff {@code Origin} host:port equals {@code Host}"
     * (it requires a fixed allowlist of origins). An explicit servlet
     * filter registration is the only mechanism that runs the
     * per-request {@link #resolve(HttpServletRequest)} logic.
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsFilter filter = new CorsFilter(this::resolve);
        FilterRegistrationBean<CorsFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/api/*");
        reg.setName("corsFilter");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    /**
     * Resolves the CORS policy for a single inbound request.
     *
     * <ul>
     *   <li>Rule 1 — {@code Origin} header absent or blank
     *   (server-to-server, curl, native fetchers): return {@code null}
     *   so Spring's {@code CorsFilter} skips CORS processing entirely.
     *   No browser is involved, so no CORS check is needed.</li>
     *
     *   <li>Rule 2 — {@code Origin} host:port equals the {@code Host}
     *   header: same-origin through the relay. Allow exactly that
     *   origin with credentials. The hostname is never hardcoded; it
     *   comes from the request itself.</li>
     *
     *   <li>Rule 3 — Cross-origin: must match {@code CORS_ALLOWED_ORIGINS}.
     *   On match, allow that origin (no credentials). Otherwise return a
     *   configuration with zero allowed origins, which Spring's
     *   {@code CorsFilter} translates into a 403 for any cross-origin
     *   request.</li>
     * </ul>
     */
    CorsConfiguration resolve(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            // Rule 1 — non-browser caller; no CORS work required.
            return null;
        }

        String originAuthority = authorityOf(origin);
        String hostHeader = request.getHeader("Host");
        if (originAuthority != null
            && hostHeader != null
            && originAuthority.equalsIgnoreCase(hostHeader)) {
            // Rule 2 — same-origin through the relay.
            return permissiveConfig(origin, true);
        }

        if (envAllowedOrigins.contains(origin)) {
            // Rule 3a — cross-origin on the operator-supplied allowlist.
            return permissiveConfig(origin, false);
        }

        // Rule 3b — cross-origin not on the allowlist. Empty
        // allowedOrigins (explicit, not null) means CorsFilter will
        // respond 403 to the preflight and to the actual request.
        CorsConfiguration denied = new CorsConfiguration();
        denied.setAllowedOrigins(List.of());
        return denied;
    }

    private CorsConfiguration permissiveConfig(String origin, boolean credentials) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.addAllowedOrigin(origin);
        cfg.setAllowedMethods(STANDARD_METHODS);
        cfg.setAllowedHeaders(ALL_HEADERS);
        cfg.setExposedHeaders(EXPOSED_HEADERS);
        cfg.setAllowCredentials(credentials);
        cfg.setMaxAge(3600L);
        return cfg;
    }

    private static String authorityOf(String origin) {
        try {
            return URI.create(origin).getAuthority();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
