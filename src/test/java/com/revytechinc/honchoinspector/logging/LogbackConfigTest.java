package com.revytechinc.honchoinspector.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code src/main/resources/logback-spring.xml} is the active
 * Logback config and registers the two expected JSONL appenders
 * ({@code FILE_JSONL} and {@code CONSOLE_JSONL}) with {@link LogstashEncoder}s.
 *
 * <p>The Spring context is needed because Logback's {@code logback-spring.xml}
 * extension is loaded by Spring's logging system during context refresh — a
 * plain JUnit test does not pick it up.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "HONCHO_DB_PATH=jdbc:sqlite::memory:",
    "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24="
})
class LogbackConfigTest {

    @Test
    void fileJsonlAppender_isRegisteredWithLogstashEncoder() {
        Appender<?> fileAppender = appenderByName("FILE_JSONL");

        assertThat(fileAppender)
            .as("FILE_JSONL appender must be registered by logback-spring.xml")
            .isNotNull();
        assertThat(fileAppender.getClass().getSimpleName())
            .isEqualTo("RollingFileAppender");
        assertThat(((OutputStreamAppender<?>) fileAppender).getEncoder())
            .isInstanceOf(LogstashEncoder.class);
    }

    @Test
    void consoleJsonlAppender_isRegisteredWithLogstashEncoder() {
        Appender<?> consoleAppender = appenderByName("CONSOLE_JSONL");

        assertThat(consoleAppender)
            .as("CONSOLE_JSONL appender must be registered by logback-spring.xml")
            .isNotNull();
        assertThat(consoleAppender.getClass().getSimpleName())
            .isEqualTo("ConsoleAppender");
        assertThat(((OutputStreamAppender<?>) consoleAppender).getEncoder())
            .isInstanceOf(LogstashEncoder.class);
    }

    @Test
    void atLeastTwoJsonlAppendersAreRegistered() {
        Map<String, Appender<?>> appenders = collectAppenders();

        assertThat(appenders.keySet())
            .as("Logback context must register the FILE_JSONL + CONSOLE_JSONL appenders")
            .contains("FILE_JSONL", "CONSOLE_JSONL");
        assertThat(appenders.size())
            .as("At least the two JSONL appenders must be present (dev profile may add CONSOLE_DEV)")
            .isGreaterThanOrEqualTo(2);
    }

    private static Appender<?> appenderByName(String name) {
        return collectAppenders().get(name);
    }

    private static Map<String, Appender<?>> collectAppenders() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Map<String, Appender<?>> all = new LinkedHashMap<>();
        Logger root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.iteratorForAppenders().forEachRemaining(a -> all.putIfAbsent(a.getName(), a));
        for (Logger logger : ctx.getLoggerList()) {
            logger.iteratorForAppenders().forEachRemaining(a -> all.putIfAbsent(a.getName(), a));
        }
        return all;
    }
}
