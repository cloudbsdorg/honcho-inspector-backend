package com.revytechinc.honchoinspector.honcho;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class HonchoOperationTest {

    /**
     * Mirror of the 24 endpoints exposed by the legacy HonchoController.
     * The controller's {@code @Get|Post|Put|Delete}Mapping count is the
     * source of truth — see HonchoController.java. If this list grows
     * beyond 24, the controller must grow with it (or vice versa).
     */
    private static final Set<HonchoOperation> EXPECTED_24 = EnumSet.of(
        HonchoOperation.LIST_PEERS,
        HonchoOperation.CREATE_PEER,
        HonchoOperation.GET_PEER_CARD,
        HonchoOperation.UPDATE_PEER_CARD,
        HonchoOperation.GET_REPRESENTATION,
        HonchoOperation.PEER_CHAT,
        HonchoOperation.SEARCH_PEERS,
        HonchoOperation.LIST_PEER_CONCLUSIONS,
        HonchoOperation.LIST_PEER_SESSIONS,
        HonchoOperation.QUERY_PEER_CONCLUSIONS,
        HonchoOperation.LIST_SESSIONS,
        HonchoOperation.CREATE_SESSION,
        HonchoOperation.GET_SESSION,
        HonchoOperation.DELETE_SESSION,
        HonchoOperation.LIST_SESSION_MESSAGES,
        HonchoOperation.ADD_MESSAGE,
        HonchoOperation.GET_SESSION_CONTEXT,
        HonchoOperation.GET_SESSION_SUMMARIES,
        HonchoOperation.GET_SESSION_PEERS,
        HonchoOperation.SEARCH_SESSION_MESSAGES,
        HonchoOperation.GET_QUEUE_STATUS,
        HonchoOperation.SEARCH_MESSAGES,
        HonchoOperation.SCHEDULE_DREAM,
        HonchoOperation.GET_WORKSPACE_INFO
    );

    private static Map<String, String> javadocByConstant = Map.of();

    @BeforeAll
    static void loadJavadocIndex() {
        try {
            String src = Files.readString(Path.of(
                "src/main/java/com/revytechinc/honchoinspector/honcho/HonchoOperation.java"));
            javadocByConstant = indexJavadoc(src);
        } catch (IOException e) {
            fail("could not read HonchoOperation.java", e);
        }
    }

    /**
     * Builds a map from each HonchoOperation constant to the Javadoc block
     * immediately above it (or empty string if there is none).
     */
    private static Map<String, String> indexJavadoc(String src) {
        Map<String, String> result = new LinkedHashMap<>();
        HonchoOperation[] ops = HonchoOperation.values();
        int cursor = 0;
        for (HonchoOperation op : ops) {
            String needle = op.name();
            int idx = src.indexOf(needle, cursor);
            assertThat(idx)
                .as("constant %s must appear in HonchoOperation.java source", needle)
                .isGreaterThanOrEqualTo(0);
            int javadocStart = src.lastIndexOf("/**", idx);
            int javadocEnd = javadocStart < 0 ? -1 : src.indexOf("*/", javadocStart);
            String block = (javadocStart >= 0 && javadocEnd >= 0 && javadocEnd < idx)
                ? src.substring(javadocStart, javadocEnd + 2)
                : "";
            result.put(needle, block);
            cursor = idx + needle.length();
        }
        return result;
    }

    @Test
    void enumHasExactly24Constants() {
        assertThat(HonchoOperation.values())
            .as("HonchoOperation must mirror the 24 endpoints in HonchoController.java")
            .hasSize(24);
    }

    @Test
    void enumMatchesExpectedInventoryExactly() {
        Set<HonchoOperation> actual = EnumSet.allOf(HonchoOperation.class);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED_24);
    }

    @Test
    void noDuplicateConstants() {
        long distinctCount = Arrays.stream(HonchoOperation.values())
            .map(Enum::name)
            .distinct()
            .count();
        assertThat(distinctCount).isEqualTo(HonchoOperation.values().length);
    }

    @Test
    void allOperationsHaveNonBlankNames() {
        for (HonchoOperation op : HonchoOperation.values()) {
            assertThat(op.name())
                .as("operation %s must have a non-blank name", op)
                .isNotBlank();
        }
    }

    @Test
    void allOperationsHaveHumanReadableName() {
        for (HonchoOperation op : HonchoOperation.values()) {
            assertThat(op.name())
                .as("operation %s must be uppercase snake_case", op)
                .matches("^[A-Z][A-Z0-9_]+$")
                .hasSizeGreaterThanOrEqualTo(3);
        }
    }

    @ParameterizedTest
    @EnumSource(HonchoOperation.class)
    void eachOperationHasJavadocMappingLegacyAndV3Paths(HonchoOperation op) {
        String javadoc = javadocByConstant.get(op.name());
        assertThat(javadoc)
            .as("operation %s must carry a Javadoc comment", op)
            .isNotBlank()
            .startsWith("/**");
        assertThat(javadoc)
            .as("operation %s Javadoc must reference the legacy /api/* endpoint", op)
            .contains("/api/");
        assertThat(javadoc)
            .as("operation %s Javadoc must reference the v3 upstream endpoint", op)
            .contains("/v3/");
    }

    @Test
    void nameLookupRoundTripsForAllOperations() {
        for (HonchoOperation op : HonchoOperation.values()) {
            assertThat(HonchoOperation.valueOf(op.name())).isSameAs(op);
        }
    }

    @Test
    void unsupportedConstantDoesNotExist() {
        Throwable thrown = capture(() -> HonchoOperation.valueOf("NOT_A_REAL_OP"));
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    private static Throwable capture(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}