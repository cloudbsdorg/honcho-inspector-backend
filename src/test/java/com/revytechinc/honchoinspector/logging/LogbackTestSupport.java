package com.revytechinc.honchoinspector.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Helpers for the logging integration tests.
 *
 * <p>Spring Boot's {@code LogbackLoggingSystem} is a JVM-wide singleton
 * (it is loaded once via {@code SpringFactoriesLoader}). The first
 * {@code @SpringBootTest} to load a Spring context triggers Logback
 * initialization, and subsequent test contexts share the same
 * {@code LoggerContext} (also a singleton per JVM) — they do <em>not</em>
 * re-run the logback config.
 *
 * <p>That means {@code ${HONCHO_CONFIG_DIR}} in {@code logback-spring.xml}
 * is resolved exactly once, against the system properties in effect at
 * the time of the first context load. Setting a system property in
 * {@code @BeforeAll} is too late: Logback has already cached the
 * resolved path. The only way to get per-test isolation is to
 * <em>force</em> the LoggerContext to re-read the config, which is what
 * {@link #reloadConfig()} does.
 */
final class LogbackTestSupport {

    private LogbackTestSupport() {}

    /**
     * Ensures {@code <configDir>/logs/} exists. Returns the resolved logs
     * directory path.
     */
    static Path prepareLogsDir(Path configDir) throws IOException {
        Path logsDir = configDir.resolve("logs");
        Files.createDirectories(logsDir);
        return logsDir;
    }

    /**
     * Forces Logback's {@code LoggerContext} to re-read
     * {@code logback-spring.xml} from the classpath. Must be called
     * <em>after</em> setting {@code HONCHO_CONFIG_DIR} (and any other
     * logback-referenced system properties) and <em>before</em> the
     * FILE_JSONL appender is used.
     */
    static void reloadConfig() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        URL resource = Thread.currentThread().getContextClassLoader()
            .getResource("logback-spring.xml");
        if (resource == null) {
            throw new IllegalStateException("logback-spring.xml not found on classpath");
        }
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        try {
            configurator.doConfigure(resource);
        } catch (JoranException e) {
            throw new IllegalStateException("Failed to reload logback-spring.xml", e);
        }
        StatusPrinter.printInCaseOfErrorsOrWarnings(context);
    }
}
