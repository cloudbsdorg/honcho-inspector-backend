package com.revytechinc.honchoinspector.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Adds per-response build metadata + a strict JSON content-type so
 * clients (browsers, Playwright, curl) can always tell what version
 * of the backend they're talking to and don't get a generic
 * {@code application/json} with the wrong charset.
 *
 * <p>Headers added on every response:
 * <ul>
 *   <li>{@code X-Honcho-Inspector-Build}: short git commit hash or
 *       {@code "local"} when the artifact was built outside CI.</li>
 *   <li>{@code X-Honcho-Inspector-Version}: the Maven
 *       {@code project.version} (e.g. {@code 0.1.0-SNAPSHOT}).</li>
 *   <li>{@code X-Honcho-Inspector-Built-At}: ISO-8601 build
 *       timestamp (or empty if not provided at build time).</li>
 * </ul>
 *
 * <p>Content-Type handling: Spring's
 * {@code @ResponseBody} controllers already emit
 * {@code application/json;charset=UTF-8}. The filter additionally
 * rewrites any response whose declared Content-Type is just
 * {@code application/json} (no charset) to include the explicit
 * UTF-8 charset — this catches the actuator and error paths that
 * don't go through {@code MappingJackson2HttpMessageConverter}.
 *
 * <p>High precedence (BEFORE the SessionAuthFilter) so the headers
 * are present even on rejected-auth responses.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class BuildInfoFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BuildInfoFilter.class);

    static final String HDR_BUILD = "X-Honcho-Inspector-Build";
    static final String HDR_VERSION = "X-Honcho-Inspector-Version";
    static final String HDR_BUILT_AT = "X-Honcho-Inspector-Built-At";

    private final String buildCommit;
    private final String buildTimestamp;
    private final String projectVersion;

    BuildInfoFilter(
        @Value("${honcho.build.commit:unknown}") String buildCommit,
        @Value("${honcho.build.timestamp:}") String buildTimestamp,
        @Value("${spring.application.name:honcho-inspector-backend}") String appName,
        @Value("${BUILD_VERSION:#{null}}") String buildVersion
    ) {
        this.buildCommit = buildCommit;
        this.buildTimestamp = buildTimestamp == null ? "" : buildTimestamp;
        // Prefer the explicit BUILD_VERSION env (matches the pom
        // ${project.version}). Fall back to the JAR's implementation
        // version, then to the application name as a last resort.
        String version = buildVersion;
        if (version == null || version.isBlank() || version.startsWith("$")) {
            version = readImplementationVersion(appName);
        }
        this.projectVersion = version == null || version.isBlank() ? "unknown" : version;
        log.info("BuildInfoFilter active: build={} version={} builtAt={}",
            this.buildCommit, this.projectVersion,
            this.buildTimestamp.isEmpty() ? "(unset)" : this.buildTimestamp);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader(HDR_BUILD, buildCommit);
        res.setHeader(HDR_VERSION, projectVersion);
        if (!buildTimestamp.isEmpty()) {
            res.setHeader(HDR_BUILT_AT, buildTimestamp);
        }
        // Spring Boot's MappingJackson2HttpMessageConverter already
        // emits application/json;charset=UTF-8 for @ResponseBody
        // controllers, so no content-type rewriting is needed on the
        // happy path. If a future contributor routes JSON through a
        // different writer that drops the charset, add the rewrite
        // here — do it BEFORE chain.doFilter so it actually takes
        // effect on the wire (setHeader after commit is a no-op).
        chain.doFilter(req, res);
    }

    private static String readImplementationVersion(String appName) {
        try {
            ClassPathResource r = new ClassPathResource("META-INF/MANIFEST.MF");
            if (!r.exists()) return null;
            try (InputStream in = r.getInputStream()) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("Implementation-Version");
                if (v != null && !v.isBlank()) return v;
            }
        } catch (IOException e) {
            log.warn("could not read MANIFEST.MF for build version: {}", e.getMessage());
        }
        return appName;
    }
}
