package com.revytechinc.honchoinspector.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HonchoContext}.
 *
 * <p>Covers the {@code apiVersion} field added in T17: the canonical
 * 5-arg constructor stores the value, the back-compat 4-arg constructor
 * defaults to {@link HonchoApiVersion#V3}, and the compact constructor
 * rejects {@code null} for any required field including {@code apiVersion}.
 */
class HonchoContextTest {

    private static final String API_KEY = "sk-test-key-1234567890";
    private static final String BASE_URL = "https://api.honcho.dev";
    private static final String WORKSPACE = "ws-1";
    private static final String USER = "alice";

    @Test
    void newConstructorExposesApiVersion() {
        var ctx = new HonchoContext(API_KEY, BASE_URL, WORKSPACE, USER, HonchoApiVersion.V2);

        assertThat(ctx.apiKey()).isEqualTo(API_KEY);
        assertThat(ctx.baseUrl()).isEqualTo(BASE_URL);
        assertThat(ctx.workspaceId()).isEqualTo(WORKSPACE);
        assertThat(ctx.userName()).isEqualTo(USER);
        assertThat(ctx.apiVersion()).isEqualTo(HonchoApiVersion.V2);
    }

    @Test
    void backwardCompatibleConstructorDefaultsToV3() {
        // The 4-arg constructor is the original signature; T15/T16 will
        // switch the call sites to the 5-arg form. Until then, the default
        // must match honcho.api-version (v3).
        var ctx = new HonchoContext(API_KEY, BASE_URL, WORKSPACE, USER);

        assertThat(ctx.apiKey()).isEqualTo(API_KEY);
        assertThat(ctx.baseUrl()).isEqualTo(BASE_URL);
        assertThat(ctx.workspaceId()).isEqualTo(WORKSPACE);
        assertThat(ctx.userName()).isEqualTo(USER);
        assertThat(ctx.apiVersion())
            .as("back-compat 4-arg ctor must default to HonchoApiVersion.V3 (the product default)")
            .isEqualTo(HonchoApiVersion.V3);
    }

    @Test
    void newConstructorRejectsNullApiVersion() {
        assertThatThrownBy(() -> new HonchoContext(API_KEY, BASE_URL, WORKSPACE, USER, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("apiVersion");
    }
}
