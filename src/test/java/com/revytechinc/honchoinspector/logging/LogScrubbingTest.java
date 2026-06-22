package com.revytechinc.honchoinspector.logging;

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
 * Verifies that {@code MaskingJsonGeneratorDecorator} scrubs API keys
 * and Bearer tokens from JSONL output.
 */
@SpringBootTest(properties = {
    "HONCHO_DB_PATH=jdbc:sqlite::memory:",
    "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24="
})
class LogScrubbingTest {

    private static final Logger LOG = LoggerFactory.getLogger(LogScrubbingTest.class);
    private static final Path LOG_DIR =
        Path.of(System.getProperty("java.io.tmpdir"), "honcho-test-log-scrubbing");
    private static final Path JSONL_FILE =
        LOG_DIR.resolve("honcho-inspector.jsonl");

    private static final String SECRET_API_KEY = "secret-123";
    private static final String SECRET_BEARER = "abc.def.ghi";

    // Captured at class load so @AfterAll can restore the surefire
    // HONCHO_LOG_DIR. See the matching field in JsonlFormatTest for the
    // full rationale.
    private static String originalLogDir;

    @BeforeAll
    static void setup() throws IOException {
        // Logback's logback-spring.xml resolves ${HONCHO_LOG_DIR} against
        // System properties, not the Spring Environment. Spring Boot's
        // LogbackLoggingSystem is a JVM-wide singleton; force-reload
        // the LoggerContext so the new path takes effect.
        originalLogDir = System.getProperty("HONCHO_LOG_DIR");
        System.setProperty("HONCHO_LOG_DIR", LOG_DIR.toString());
        if (Files.exists(JSONL_FILE)) {
            Files.delete(JSONL_FILE);
        }
        LogbackTestSupport.prepareLogDir(LOG_DIR);
        // Use the heavy XML re-parse — see JsonlFormatTest for the
        // rationale. The lightweight reloadConfig() re-point has no
        // effect because Spring's LogbackLoggingSystem has already
        // replaced the appender by the time @BeforeAll runs.
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
    void apiKeyAndBearerTokens_areScrubbedInJsonl() throws IOException {
        String marker = "log-scrubbing-marker-" + System.nanoTime();
        LOG.info("{} apiKey={} with Bearer {}", marker, SECRET_API_KEY, SECRET_BEARER);

        assertThat(JSONL_FILE).exists();

        List<String> lines = Files.readAllLines(JSONL_FILE).stream()
            .filter(l -> l.contains(marker))
            .toList();
        String content = String.join("\n", lines);

        assertThat(lines)
            .as("at least one marker line should have been written")
            .isNotEmpty();
        assertThat(content)
            .as("apiKey secret must be scrubbed from JSONL")
            .doesNotContain(SECRET_API_KEY);
        assertThat(content)
            .as("Bearer token must be scrubbed from JSONL")
            .doesNotContain(SECRET_BEARER);
        assertThat(content)
            .as("the scrub marker *** must appear in the JSONL")
            .contains("***");
    }
}
