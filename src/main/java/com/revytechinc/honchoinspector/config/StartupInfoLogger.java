package com.revytechinc.honchoinspector.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Logs the active externalized configuration once the application is ready.
 * Useful in containerized deployments where you want a single log line to confirm
 * which env vars / profiles / ports / CORS origins the process is actually using.
 */
@Component
public class StartupInfoLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupInfoLogger.class);

    private final Environment env;
    private final HonchoConfigDirResolver configDir;
    private final String port;
    private final String honchoBaseUrl;
    private final String honchoApiVersion;
    private final long honchoTimeoutMs;
    private final String corsOrigins;
    private final long sessionTtlMinutes;

    public StartupInfoLogger(
        Environment env,
        HonchoConfigDirResolver configDir,
        @Value("${server.port:8080}") String port,
        @Value("${honcho.base-url:https://api.honcho.dev}") String honchoBaseUrl,
        @Value("${honcho.api-version:v3}") String honchoApiVersion,
        @Value("${honcho.request-timeout-ms:30000}") long honchoTimeoutMs,
        @Value("${cors.allowed-origins:http://localhost:4200}") String corsOrigins,
        @Value("${session.ttl-minutes:0}") long sessionTtlMinutes
    ) {
        this.env = env;
        this.configDir = configDir;
        this.port = port;
        this.honchoBaseUrl = honchoBaseUrl;
        this.honchoApiVersion = honchoApiVersion;
        this.honchoTimeoutMs = honchoTimeoutMs;
        this.corsOrigins = corsOrigins;
        this.sessionTtlMinutes = sessionTtlMinutes;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logStartup() {
        var profiles = Arrays.stream(env.getActiveProfiles())
            .filter(p -> !p.isBlank())
            .collect(Collectors.joining(","));
        var profileStr = profiles.isEmpty() ? "default" : profiles;
        var configDirPath = configDir != null ? configDir.resolve().toString() : "(resolver not available)";
        log.info(
            "honcho-inspector backend ready: port={}, profiles=[{}], config-dir={}, honcho={} (api={}, timeout={}ms), cors={}, session-ttl={}m",
            port, profileStr, configDirPath, honchoBaseUrl, honchoApiVersion, honchoTimeoutMs, corsOrigins, sessionTtlMinutes
        );
    }
}
