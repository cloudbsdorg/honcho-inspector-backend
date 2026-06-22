package com.revytechinc.honchoinspector.honcho.v3;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contract test for {@link WorkspaceProviderV3}: the single-op provider
 * that handles {@code GET_WORKSPACE_INFO} as a connectivity probe.
 *
 * <p>Honcho v3 does not expose a {@code GET /v3/workspaces/{id}} endpoint
 * (the path only accepts {@code PUT} and {@code DELETE}). This provider
 * therefore proxies the probe through the queue-status endpoint, which
 * is GET, requires no body, and returns real workspace-scoped data.
 *
 * <p>Asserts the four metadata points the registry relies on:
 * the {@code operations()} set, {@code supportedVersions()}, the
 * path template, and the HTTP method.
 */
class WorkspaceProviderV3Test {

    @Test
    void metadataMatchesV3Contract() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(mock(RestClient.class));
        WorkspaceProviderV3 provider = new WorkspaceProviderV3(builder);

        assertThat(provider.operations())
            .containsExactly(HonchoOperation.GET_WORKSPACE_INFO);
        assertThat(provider.supportedVersions())
            .containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.GET_WORKSPACE_INFO))
            .isEqualTo("workspaces/{ws}/queue/status");
        assertThat(provider.httpMethod(HonchoOperation.GET_WORKSPACE_INFO))
            .isEqualTo(HttpMethod.GET);
    }
}
