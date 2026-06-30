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
 * Useful in containerized deployments where you want a single log line to
 * confirm which env vars / profiles / ports the process is actually using.
 *
 * <p>CORS is intentionally <strong>not</strong> logged: there is no
 * hardcoded CORS configuration. Cross-origin access is opt-in via the
 * {@code CORS_ALLOWED_ORIGINS} env var, which is read by
 * {@link CorsConfig}; same-origin (via the relay) is always allowed.
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
    private final long sessionTtlMinutes;
    private final boolean chatEnabled;

    public StartupInfoLogger(
        Environment env,
        HonchoConfigDirResolver configDir,
        @Value("${server.port:8080}") String port,
        @Value("${honcho.base-url:https://api.honcho.dev}") String honchoBaseUrl,
        @Value("${honcho.api-version:v3}") String honchoApiVersion,
        @Value("${honcho.request-timeout-ms:30000}") long honchoTimeoutMs,
        @Value("${session.ttl-minutes:0}") long sessionTtlMinutes,
        @Value("${honcho.ui.chat-enabled:false}") boolean chatEnabled
    ) {
        this.env = env;
        this.configDir = configDir;
        this.port = port;
        this.honchoBaseUrl = honchoBaseUrl;
        this.honchoApiVersion = honchoApiVersion;
        this.honchoTimeoutMs = honchoTimeoutMs;
        this.sessionTtlMinutes = sessionTtlMinutes;
        this.chatEnabled = chatEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logStartup() {
        var profiles = Arrays.stream(env.getActiveProfiles())
            .filter(p -> !p.isBlank())
            .collect(Collectors.joining(","));
        var profileStr = profiles.isEmpty() ? "default" : profiles;
        var configDirEntry = formatConfigDir(configDir);
        log.info(
            "honcho-inspector backend ready: port={}, profiles=[{}], config-dir={}, honcho={} (api={}, timeout={}ms), session-ttl={}m, chat-enabled={}",
            port, profileStr, configDirEntry, honchoBaseUrl, honchoApiVersion, honchoTimeoutMs, sessionTtlMinutes, chatEnabled
        );
    }

    private static String formatConfigDir(HonchoConfigDirResolver resolver) {
        if (resolver == null) {
            return "(resolver not available)";
        }
        HonchoConfigDirResolver.ResolveResult result = resolver.resolveOrCreate();
        String pathStr = result.path().toString();
        return switch (result.status()) {
            case CREATED -> pathStr + " [created]";
            case EXISTS -> pathStr + " [exists]";
            case FALLBACK -> pathStr + " [fallback: " + pathStr + "]";
        };
    }
}
