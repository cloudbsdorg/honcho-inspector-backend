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
 * Contract test for {@link QueueStatusProviderV3}: the single-op
 * provider that handles {@code GET /v3/workspaces/{ws}/queue/status}.
 * Confirmed against {@code https://honcho.cloudbsd.org/openapi.json}.
 */
class QueueStatusProviderV3Test {

    @Test
    void metadataMatchesV3Contract() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(mock(RestClient.class));
        QueueStatusProviderV3 provider = new QueueStatusProviderV3(builder);

        assertThat(provider.operations())
            .containsExactly(HonchoOperation.GET_QUEUE_STATUS);
        assertThat(provider.supportedVersions())
            .containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.GET_QUEUE_STATUS))
            .isEqualTo("workspaces/{ws}/queue/status");
        assertThat(provider.httpMethod(HonchoOperation.GET_QUEUE_STATUS))
            .isEqualTo(HttpMethod.GET);
    }
}
