package com.revytechinc.honchoinspector.honcho;

import com.revytechinc.honchoinspector.config.HonchoProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the {@link HonchoProperties} {@code @ConfigurationProperties}
 * bean (T4) correctly binds to env vars ({@code HONCHO_API_VERSION},
 * {@code HONCHO_PROVIDERS_STRICT_MODE}) and to the relaxed Java defaults
 * (apiVersion=v3, strictMode=false) when no overrides are supplied.
 */
class HonchoPropertiesTest {

    @SpringBootTest(classes = HonchoPropertiesTest.TestConfig.class)
    @TestPropertySource(properties = {
        "HONCHO_DB_PATH=jdbc:sqlite::memory:",
        "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24="
    })
    static class Defaults {
        @Autowired HonchoProperties properties;

        @Test
        void defaults_applyWhenEnvUnset() {
            assertThat(properties.apiVersion())
                .as("default apiVersion must be v3 when no env override is set")
                .isEqualTo("v3");
            assertThat(properties.providers().strictMode())
                .as("default providers.strictMode must be false when no env override is set")
                .isFalse();
            assertThat(properties.baseUrl())
                .as("default baseUrl must be https://api.honcho.dev when no env override is set")
                .isEqualTo("https://api.honcho.dev");
        }
    }

    @SpringBootTest(classes = HonchoPropertiesTest.TestConfig.class)
    @TestPropertySource(properties = {
        "honcho.api-version=v4",
        "honcho.providers.strict-mode=true",
        "honcho.base-url=https://honcho.example.com",
        "HONCHO_DB_PATH=jdbc:sqlite::memory:",
        "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24="
    })
    static class EnvOverrides {
        @Autowired HonchoProperties properties;

        @Test
        void envBindsToHonchoProperties() {
            assertThat(properties.apiVersion())
                .as("honcho.api-version must bind to HonchoProperties.apiVersion")
                .isEqualTo("v4");
            assertThat(properties.providers().strictMode())
                .as("honcho.providers.strict-mode must bind to HonchoProperties.providers.strictMode")
                .isTrue();
            assertThat(properties.baseUrl())
                .as("honcho.base-url must bind to HonchoProperties.baseUrl")
                .isEqualTo("https://honcho.example.com");
        }
    }

    @SpringBootTest(classes = HonchoPropertiesTest.TestConfig.class)
    @TestPropertySource(properties = {
        "honcho.api-version=V4",
        "honcho.providers.strict-mode=true",
        "HONCHO_DB_PATH=jdbc:sqlite::memory:",
        "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24="
    })
    static class EnvUpperCase {
        @Autowired HonchoProperties properties;

        @Test
        void relaxedBinding_acceptsUpperCase() {
            assertThat(properties.apiVersion()).isEqualTo("V4");
            assertThat(properties.providers().strictMode()).isTrue();
        }
    }

    @EnableConfigurationProperties(HonchoProperties.class)
    static class TestConfig {}
}
