package com.revytechinc.honchoinspector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed binding for the {@code honcho.*} block of application.yml.
 * Replaces scattered {@code @Value} injections so future keys can be added
 * without editing every consumer. Nested groups (e.g. {@code providers.strictMode},
 * {@code log.maxFileSize}) bind via dotted YAML keys — no SpEL required.
 *
 * <p>Spring Boot's relaxed binding means each component has a sensible Java
 * default if the YAML key is absent. The bundled {@code application.yml}
 * always supplies {@code baseUrl}, {@code apiVersion}, and
 * {@code requestTimeoutMs}, so defaults only kick in for partial overrides
 * from a drop-in config.
 */
@ConfigurationProperties(prefix = "honcho")
public record HonchoProperties(
    String baseUrl,
    String apiVersion,
    long requestTimeoutMs,
    Providers providers,
    Log log
) {
    public HonchoProperties {
        if (baseUrl == null) baseUrl = "https://api.honcho.dev";
        if (apiVersion == null) apiVersion = "v3";
        if (providers == null) providers = new Providers(false);
        if (log == null) log = new Log("INFO", "100MB", 30, "500MB");
    }

    public record Providers(boolean strictMode) {}

    /**
     * T4b (JSONL logging) — knobs that drive the Logback rolling policy.
     * All values are kept as strings so the raw {@code 100MB} / {@code 500MB}
     * size literals from {@code application.yml} survive the binding (Logback
     * understands these tokens directly).
     */
    public record Log(
        String level,
        String maxFileSize,
        int maxHistory,
        String totalSizeCap
    ) {
        public Log {
            if (level == null || level.isBlank()) level = "INFO";
            if (maxFileSize == null || maxFileSize.isBlank()) maxFileSize = "100MB";
            if (totalSizeCap == null || totalSizeCap.isBlank()) totalSizeCap = "500MB";
            if (maxHistory <= 0) maxHistory = 30;
        }
    }
}
