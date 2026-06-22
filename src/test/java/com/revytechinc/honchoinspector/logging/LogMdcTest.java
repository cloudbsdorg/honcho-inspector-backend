package com.revytechinc.honchoinspector.logging;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link MDC} fields ({@code session_id}, {@code user_id})
 * are emitted in JSONL events when set, and are absent after
 * {@link MDC#clear()} runs.
 *
 * <p>Mirrors the pattern used by {@code SessionAuthFilter}:
 * {@code MDC.put} before logging, {@code MDC.remove} / {@code MDC.clear}
 * in a {@code finally} block to prevent leakage across requests.
 */
@SpringBootTest(properties = {
    "HONCHO_DB_PATH=jdbc:sqlite::memory:",
    "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24="
})
class LogMdcTest {

    private static final Logger LOG = LoggerFactory.getLogger(LogMdcTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CONFIG_DIR =
        Path.of(System.getProperty("java.io.tmpdir"), "honcho-test-log-mdc");
    private static final Path JSONL_FILE =
        CONFIG_DIR.resolve("logs").resolve("honcho-inspector.jsonl");

    @BeforeAll
    static void setup() throws IOException {
        // Logback's logback-spring.xml resolves ${HONCHO_CONFIG_DIR} against
        // System properties, not the Spring Environment. Spring Boot's
        // LogbackLoggingSystem is a JVM-wide singleton; force-reload
        // the LoggerContext so the new path takes effect.
        System.setProperty("HONCHO_CONFIG_DIR", CONFIG_DIR.toString());
        if (Files.exists(JSONL_FILE)) {
            Files.delete(JSONL_FILE);
        }
        LogbackTestSupport.prepareLogsDir(CONFIG_DIR);
        LogbackTestSupport.reloadConfig();
    }

    @AfterAll
    static void cleanup() throws IOException {
        System.clearProperty("HONCHO_CONFIG_DIR");
        if (Files.exists(CONFIG_DIR)) {
            try (Stream<Path> walk = Files.walk(CONFIG_DIR)) {
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
    void mdcFieldsAppearWhenSet_andAreAbsentAfterClear() throws IOException {
        String withMarker = "mdc-test-with-fields-" + System.nanoTime();
        String afterMarker = "mdc-test-after-clear-" + System.nanoTime();

        try {
            MDC.put("session_id", "sess-abc-123");
            MDC.put("user_id", "user-42");
            LOG.info(withMarker);
        } finally {
            MDC.remove("session_id");
            MDC.remove("user_id");
        }

        MDC.clear();
        LOG.info(afterMarker);

        assertThat(JSONL_FILE).exists();

        List<String> lines = Files.readAllLines(JSONL_FILE).stream()
            .filter(l -> !l.isBlank())
            .filter(l -> l.contains(withMarker) || l.contains(afterMarker))
            .toList();

        JsonNode withFields = findLineByMarker(lines, withMarker);
        JsonNode afterClear = findLineByMarker(lines, afterMarker);

        assertThat(withFields.get("session_id").asText())
            .as("session_id must appear in the MDC-tagged event")
            .isEqualTo("sess-abc-123");
        assertThat(withFields.get("user_id").asText())
            .as("user_id must appear in the MDC-tagged event")
            .isEqualTo("user-42");

        assertThat(afterClear.has("session_id"))
            .as("session_id must be absent after MDC.clear()")
            .isFalse();
        assertThat(afterClear.has("user_id"))
            .as("user_id must be absent after MDC.clear()")
            .isFalse();
    }

    private static JsonNode findLineByMarker(List<String> lines, String marker) throws IOException {
        for (String line : lines) {
            if (line.contains(marker)) {
                return MAPPER.readTree(line);
            }
        }
        throw new AssertionError("No log line found with marker=" + marker);
    }
}
