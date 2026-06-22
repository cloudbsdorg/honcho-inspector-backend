package com.revytechinc.honchoinspector.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.rolling.RollingFileAppender;
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
 * <p>That means {@code ${HONCHO_LOG_DIR}} in {@code logback-spring.xml}
 * is resolved exactly once, against the system properties in effect at
 * the time of the first context load. Setting a system property in
 * {@code @BeforeAll} is too late: Logback has already cached the
 * resolved path. The only way to get per-test isolation is to
 * <em>force</em> the LoggerContext to re-read the config, which is what
 * {@link #reloadConfig()} does.
 *
 * <h2>Why we re-point the appender instead of re-parsing the XML</h2>
 * An earlier version of this helper called
 * {@link JoranConfigurator#doConfigure(URL)} on the bare logback
 * configurator, which loses Spring Boot's {@code SpringBootJoranConfigurator}
 * hooks — most importantly the resolution of {@code <springProfile>} and
 * {@code <springProperty>} elements. After the first reload, subsequent
 * tests that re-load the Spring context (e.g. {@code LogbackConfigTest})
 * would see a context where FILE_JSONL pointed at whatever path the
 * most-recent test had set, and Spring would re-resolve LOG_DIR from
 * the system properties at the time of context boot, falling back to
 * the production default {@code /var/log/honcho-inspector} if no
 * {@code HONCHO_LOG_DIR} was set — which then crashed the next test
 * with a {@code FileNotFoundException}. Re-pointing the appender
 * preserves the original Spring-resolved config and only changes the
 * single file path the test cares about.
 */
final class LogbackTestSupport {

    private LogbackTestSupport() {}

    /**
     * Ensures the JSONL log directory exists. Returns the resolved path.
     * As of the logback config that pins {@code LOG_DIR} directly to
     * {@code ${HONCHO_LOG_DIR}} (no {@code /logs/} suffix — see the
     * rationale block in {@code logback-spring.xml}), the JSONL file
     * lives at {@code <logDir>/honcho-inspector.jsonl}, so this helper
     * just ensures {@code logDir} itself exists.
     */
    static Path prepareLogDir(Path logDir) throws IOException {
        Files.createDirectories(logDir);
        return logDir;
    }

    /**
     * Backwards-compat alias: the old helper name expected a {@code logs/}
     * subdir under the config dir. The flat layout means the "logs" dir
     * IS the supplied path. Kept so older tests compile without churn.
     */
    static Path prepareLogsDir(Path logDir) throws IOException {
        return prepareLogDir(logDir);
    }

    /**
     * Repoints the {@code FILE_JSONL} appender's active file to
     * {@code <newLogDir>/honcho-inspector.jsonl} without re-parsing
     * {@code logback-spring.xml}. Must be called <em>after</em> the
     * Spring context has been initialized (so the appender exists) and
     * <em>after</em> setting {@code HONCHO_LOG_DIR} (so subsequent
     * context reloads still see the right value).
     *
     * <p>This is intentionally lighter than re-running Joran:
     * Spring-aware directives in the XML (e.g. {@code <springProfile>},
     * {@code <springProperty>}) stay intact, so other tests in the
     * same JVM that load a fresh Spring context after this one still
     * get a properly-configured Logback.
     */
    static void reloadConfig() {
        String logDir = System.getProperty("HONCHO_LOG_DIR");
        if (logDir == null || logDir.isBlank()) {
            return;
        }
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Appender<?> appender = context.getLogger(Logger.ROOT_LOGGER_NAME)
            .getAppender("FILE_JSONL");
        if (appender instanceof RollingFileAppender<?> rfa) {
            rfa.stop();
            rfa.setFile(logDir + "/honcho-inspector.jsonl");
            rfa.setContext(context);
            rfa.start();
        }
    }

    /**
     * Heavy reload: re-parses {@code logback-spring.xml} from the
     * classpath. Use this when the test needs to override XML-level
     * settings that the lightweight {@link #reloadConfig()} cannot
     * change (e.g. {@code HONCHO_LOG_MAX_FILE_SIZE} and
     * {@code HONCHO_LOG_TOTAL_SIZE_CAP}, which are read at
     * {@code RollingFileAppender} construction time and cannot be
     * re-pointed after the appender is built).
     *
     * <p>This uses the bare logback {@link JoranConfigurator} (not
     * Spring Boot's {@code SpringBootJoranConfigurator}), so any
     * {@code <springProfile>} / {@code <springProperty>} directives in
     * the XML are skipped on this reload. That is intentional: the
     * system properties that control rotation (sizes, totals) are read
     * by the bare logback property resolver, and skipping the
     * Spring-specific directives avoids re-running the full Spring
     * profile logic on a hot path.
     */
    static void reloadConfigWithXmlReparse() {
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
