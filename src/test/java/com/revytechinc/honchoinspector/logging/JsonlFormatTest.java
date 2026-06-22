package com.revytechinc.honchoinspector.logging;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that log lines written by the {@code FILE_JSONL} appender are
 * valid JSONL and contain the documented event schema.
 *
 * <p>Uses a per-class {@code HONCHO_LOG_DIR} (set as a system property
 * in a {@code static} initializer block, before Spring loads any context)
 * so the test runs in isolation from other logging tests and from the
 * production {@code /var/log/honcho-inspector} directory.
 *
 * <p>The path is now flat — the JSONL file lives at
 * {@code ${HONCHO_LOG_DIR}/honcho-inspector.jsonl}, not
 * {@code ${HONCHO_LOG_DIR}/logs/honcho-inspector.jsonl}. The previous
 * design appended {@code /logs/} which broke under systemd's
 * {@code ProtectSystem=strict}: the log dir at
 * {@code /var/log/honcho-inspector} is writable but a hypothetical
 * {@code /var/log/honcho-inspector/logs/} subdir would not be
 * auto-created by {@code RollingFileAppender}. See the rationale block
 * in {@code logback-spring.xml} for the full context.
 */
@SpringBootTest(properties = {
    "HONCHO_DB_PATH=jdbc:sqlite::memory:",
    "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24="
})
class JsonlFormatTest {

    private static final Path LOG_DIR =
        Path.of(System.getProperty("java.io.tmpdir"), "honcho-test-jsonl-format");
    private static final Path JSONL_FILE =
        LOG_DIR.resolve("honcho-inspector.jsonl");
    private static final Logger LOG = LoggerFactory.getLogger(JsonlFormatTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Captured at class load so @AfterAll can restore the surefire
    // HONCHO_LOG_DIR. Without this, the next @SpringBootTest in the same
    // JVM would re-resolve ${HONCHO_LOG_DIR:-/var/log/honcho-inspector}
    // and crash the context load with the "ApplicationContext failure
    // threshold (1) exceeded" cascade.
    private static String originalLogDir;

    // Logback's logback-spring.xml resolves ${HONCHO_LOG_DIR} against
    // System properties, not the Spring Environment. Spring Boot's
    // LogbackLoggingSystem is a JVM-wide singleton initialized by the
    // first @SpringBootTest to load a context; subsequent contexts share
    // the same LoggerContext and do NOT re-read the logback config. So
    // the test must (a) set the system property, then (b) force-reload
    // the LoggerContext to pick up the new value.
    static {
        originalLogDir = System.getProperty("HONCHO_LOG_DIR");
        System.setProperty("HONCHO_LOG_DIR", LOG_DIR.toString());
    }

    @BeforeAll
    static void setup() throws IOException {
        if (Files.exists(JSONL_FILE)) {
            Files.delete(JSONL_FILE);
        }
        LogbackTestSupport.prepareLogDir(LOG_DIR);
        // Use the heavy XML re-parse so the FILE_JSONL appender's
        // RollingFileAppender is fully re-initialized (the lightweight
        // re-point in reloadConfig() does not work — Spring's
        // LogbackLoggingSystem has already replaced the appender
        // before the @BeforeAll hook runs, so re-pointing it has no
        // effect on the appender that the LoggerContext actually
        // uses for new log events).
        LogbackTestSupport.reloadConfigWithXmlReparse();
    }

    @AfterAll
    static void cleanup() throws IOException {
        if (originalLogDir != null) {
            System.setProperty("HONCHO_LOG_DIR", originalLogDir);
        } else {
            System.clearProperty("HONCHO_LOG_DIR");
        }
        if (Files.exists(LOG_DIR)) {
            try (Stream<Path> walk = Files.walk(LOG_DIR)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort cleanup; temp dir will be GC'd
                        }
                    });
            }
        }
    }

    @Test
    void jsonlLines_areValidJsonWithExpectedSchema() throws IOException {
        String marker = "jsonl-format-test-marker-" + System.nanoTime();
        LOG.info("{} info", marker);
        LOG.warn("{} warn", marker);
        LOG.error("{} error", marker);

        assertThat(JSONL_FILE)
            .as("FILE_JSONL appender should have written to %s", JSONL_FILE)
            .exists();

        List<String> lines = Files.readAllLines(JSONL_FILE).stream()
            .filter(line -> !line.isBlank())
            .filter(line -> line.contains(marker))
            .toList();

        assertThat(lines)
            .as("at least the 3 marker log lines should have been written")
            .hasSizeGreaterThanOrEqualTo(3);

        boolean sawInfo = false;
        for (String line : lines) {
            JsonNode node = MAPPER.readTree(line);
            assertThat(node.isObject())
                .as("every line must be a JSON object: %s", line)
                .isTrue();

            assertThat(node.has("@timestamp")).as("@timestamp present in %s", line).isTrue();
            assertThat(node.has("level")).as("level present in %s", line).isTrue();
            assertThat(node.has("message")).as("message present in %s", line).isTrue();
            assertThat(node.has("logger_name")).as("logger_name present in %s", line).isTrue();
            assertThat(node.has("service")).as("service present in %s", line).isTrue();
            assertThat(node.has("version")).as("version present in %s", line).isTrue();

            assertThat(node.get("service").asText()).isEqualTo("honcho-inspector-backend");
            assertThat(node.get("logger_name").asText()).isEqualTo(JsonlFormatTest.class.getName());

            if (line.contains("\"INFO\"")) {
                sawInfo = true;
            }
        }

        assertThat(sawInfo)
            .as("expected to find an INFO-level marker line in the JSONL output")
            .isTrue();
    }
}
