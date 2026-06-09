package com.honcho.dashboard.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistration;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    @Test
    void usesCommaSeparatedOriginsFromProperty() {
        var cfg = new CorsConfig("https://a.example,https://b.example");
        var captured = capture(cfg);
        assertThat(captured.getAllowedOrigins())
            .containsExactly("https://a.example", "https://b.example");
    }

    @Test
    void trimsWhitespaceAndIgnoresEmptyEntries() {
        var cfg = new CorsConfig("  https://a.example  ,  ,https://b.example");
        var captured = capture(cfg);
        assertThat(captured.getAllowedOrigins())
            .containsExactly("https://a.example", "https://b.example");
    }

    @Test
    void allowsAllStandardHttpMethods() {
        var cfg = new CorsConfig("https://x.example");
        var captured = capture(cfg);
        assertThat(captured.getAllowedMethods())
            .contains("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }

    @Test
    void exposesSessionAndProfileIdHeaders() {
        var cfg = new CorsConfig("https://x.example");
        var captured = capture(cfg);
        assertThat(captured.getExposedHeaders())
            .contains("X-Session-Id", "X-Honcho-Profile-Id");
    }

    @Test
    void singleOriginWorks() {
        var cfg = new CorsConfig("https://honcho.example.com");
        var captured = capture(cfg);
        assertThat(captured.getAllowedOrigins())
            .containsExactly("https://honcho.example.com");
    }

    private CorsConfiguration capture(CorsConfig cfg) {
        var reg = new CorsRegistration("/api/**");
        cfg.addCorsMappings(new RecordingRegistry(reg));
        try {
            var f = CorsRegistration.class.getDeclaredField("config");
            f.setAccessible(true);
            return (CorsConfiguration) f.get(reg);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static final class RecordingRegistry extends org.springframework.web.servlet.config.annotation.CorsRegistry {
        private final CorsRegistration reg;
        RecordingRegistry(CorsRegistration reg) { this.reg = reg; }
        @Override
        public CorsRegistration addMapping(String path) {
            return reg;
        }
    }
}
