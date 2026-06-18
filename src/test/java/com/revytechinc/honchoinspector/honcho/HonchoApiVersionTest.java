package com.revytechinc.honchoinspector.honcho;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HonchoApiVersionTest {

    @Test
    void enumHasExactlyThreeConstants() {
        assertThat(HonchoApiVersion.values())
            .as("HonchoApiVersion is the spine of v2/v3/v4 routing; do not add or remove constants casually")
            .containsExactly(HonchoApiVersion.V2, HonchoApiVersion.V3, HonchoApiVersion.V4);
    }

    @Test
    void pathPrefixMatchesEnumNameLowercased() {
        assertThat(HonchoApiVersion.V2.pathPrefix()).isEqualTo("v2");
        assertThat(HonchoApiVersion.V3.pathPrefix()).isEqualTo("v3");
        assertThat(HonchoApiVersion.V4.pathPrefix()).isEqualTo("v4");
    }

    @ParameterizedTest
    @CsvSource({
        "v2, V2",
        "V2, V2",
        "v3, V3",
        "V3, V3",
        "v4, V4",
        "V4, V4"
    })
    void fromStringAcceptsCanonicalAndLowercase(String input, String expectedName) {
        HonchoApiVersion parsed = HonchoApiVersion.fromString(input);
        assertThat(parsed.name()).isEqualTo(expectedName);
    }

    @Test
    void fromStringIsCaseInsensitive() {
        assertThat(HonchoApiVersion.fromString("v3")).isEqualTo(HonchoApiVersion.V3);
        assertThat(HonchoApiVersion.fromString("V3")).isEqualTo(HonchoApiVersion.V3);
        assertThat(HonchoApiVersion.fromString("v4")).isEqualTo(HonchoApiVersion.V4);
        assertThat(HonchoApiVersion.fromString("V4")).isEqualTo(HonchoApiVersion.V4);
    }

    @Test
    void fromStringRejectsUnknownVersionWithHelpfulMessage() {
        assertThatThrownBy(() -> HonchoApiVersion.fromString("v99"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("v99")
            .hasMessageContaining("V2")
            .hasMessageContaining("V3")
            .hasMessageContaining("V4");
    }

    @Test
    void fromStringRejectsNullWithHelpfulMessage() {
        assertThatThrownBy(() -> HonchoApiVersion.fromString(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("null")
            .hasMessageContaining("V2")
            .hasMessageContaining("V3")
            .hasMessageContaining("V4");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "v1", "V5", "vv3", "three", "v-3"})
    void fromStringRejectsGarbage(String input) {
        assertThatThrownBy(() -> HonchoApiVersion.fromString(input))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Supported versions");
    }

    @Test
    void supportedVersionsListsAllThree() {
        String supported = HonchoApiVersion.supportedVersions();
        assertThat(supported)
            .contains("V2 (v2)")
            .contains("V3 (v3)")
            .contains("V4 (v4)");
    }

    @Test
    void errorMessageUsesSupportedVersionsHelper() {
        String msg = catchIllegal(() -> HonchoApiVersion.fromString("v99"));
        assertThat(msg).isEqualTo(
            "Unknown Honcho API version: 'v99'. Supported versions: " + HonchoApiVersion.supportedVersions()
        );
    }

    private static String catchIllegal(Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }
}