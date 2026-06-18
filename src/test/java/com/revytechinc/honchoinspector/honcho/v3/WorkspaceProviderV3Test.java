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
 * that handles {@code GET /v3/workspaces/{ws}}.
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
            .isEqualTo("workspaces/{ws}");
        assertThat(provider.httpMethod(HonchoOperation.GET_WORKSPACE_INFO))
            .isEqualTo(HttpMethod.GET);
    }
}
