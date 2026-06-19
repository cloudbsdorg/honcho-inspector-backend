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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the {@code FILE_JSONL} rolling policy actually rotates
 * under size pressure and prunes files beyond the {@code totalSizeCap}.
 *
 * <p>The {@code HONCHO_LOG_MAX_FILE_SIZE=1KB} and
 * {@code HONCHO_LOG_TOTAL_SIZE_CAP=100KB} overrides are set as system
 * properties in {@code @BeforeAll} (before Spring loads the context) so
 * Logback's {@code SizeAndTimeBasedRollingPolicy} initializes with the
 * small budget from the start. Mutating the policy's fields after the
 * appender is already started is unreliable (the policy was constructed
 * with the default 100MB cap and the new value is not picked up), so
 * config-time is the only reliable approach.
 *
 * <p>{@code maxHistory} is intentionally <em>not</em> set: it is
 * time-based and would not prune same-day rotations, so it cannot
 * bound the file count for a sub-second test that runs thousands of
 * rollovers. {@code totalSizeCap} is the only knob that prunes
 * size-driven rotation in the same day.
 */
@SpringBootTest(properties = {
    "HONCHO_DB_PATH=jdbc:sqlite::memory:",
    "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24="
})
class LogRotationTest {

    private static final Logger LOG = LoggerFactory.getLogger(LogRotationTest.class);
    private static final Path CONFIG_DIR =
        Path.of(System.getProperty("java.io.tmpdir"), "honcho-test-log-rotation");
    private static final Path LOGS_DIR = CONFIG_DIR.resolve("logs");

    @BeforeAll
    static void setup() throws IOException {
        // Logback's logback-spring.xml resolves ${HONCHO_CONFIG_DIR},
        // ${HONCHO_LOG_MAX_FILE_SIZE}, and ${HONCHO_LOG_TOTAL_SIZE_CAP}
        // against System properties, not the Spring Environment. Spring
        // Boot's LogbackLoggingSystem is a JVM-wide singleton; force-reload
        // the LoggerContext so the new values take effect.
        System.setProperty("HONCHO_CONFIG_DIR", CONFIG_DIR.toString());
        System.setProperty("HONCHO_LOG_MAX_FILE_SIZE", "1KB");
        System.setProperty("HONCHO_LOG_TOTAL_SIZE_CAP", "100KB");
        if (Files.exists(LOGS_DIR)) {
            try (Stream<Path> walk = Files.walk(LOGS_DIR)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort cleanup of stale rotation artifacts
                        }
                    });
            }
        }
        LogbackTestSupport.prepareLogsDir(CONFIG_DIR);
        LogbackTestSupport.reloadConfig();
    }

    @AfterAll
    static void cleanup() throws IOException {
        System.clearProperty("HONCHO_CONFIG_DIR");
        System.clearProperty("HONCHO_LOG_MAX_FILE_SIZE");
        System.clearProperty("HONCHO_LOG_TOTAL_SIZE_CAP");
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
    void rotatesUnderSizePressure_andRespectsTotalSizeCap() throws IOException {
        for (int i = 0; i < 10_000; i++) {
            LOG.info("rotation-marker-{}", i);
        }

        assertThat(LOGS_DIR).exists();

        long jsonlGzCount;
        try (Stream<Path> files = Files.list(LOGS_DIR)) {
            jsonlGzCount = files.filter(p -> p.getFileName().toString().endsWith(".jsonl.gz")).count();
        }

        long totalSizeBytes;
        try (Stream<Path> files = Files.list(LOGS_DIR)) {
            totalSizeBytes = files.filter(Files::isRegularFile)
                .mapToLong(p -> {
                    try {
                        return Files.size(p);
                    } catch (IOException e) {
                        return 0L;
                    }
                })
                .sum();
        }

        assertThat(jsonlGzCount)
            .as("at least one rotated .jsonl.gz file must exist (10k events at 1KB max file size)")
            .isGreaterThanOrEqualTo(1);

        // totalSizeCap=100KB should prune aggressively; allow 50% overhead for
        // compression ratio variation and the active file's pending writes.
        assertThat(totalSizeBytes)
            .as("total bytes on disk must stay within totalSizeCap=100KB (pruning is working)")
            .isLessThanOrEqualTo(150 * 1024L);

        try (Stream<Path> files = Files.list(LOGS_DIR)) {
            files.filter(p -> p.getFileName().toString().contains(".jsonl"))
                .filter(p -> !p.getFileName().toString().equals("honcho-inspector.jsonl"))
                .forEach(p -> assertThat(p.getFileName().toString())
                    .as("rotated file must end in .jsonl.gz: %s", p.getFileName())
                    .endsWith(".jsonl.gz"));
        }
    }
}

