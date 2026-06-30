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
 * Contract test for {@link DreamsProviderV3}: the single-op provider
 * that handles {@code POST /v3/workspaces/{ws}/schedule_dream}. The
 * v2 legacy {@code /api/dream} was workspace-scoped and took the peer
 * id in the body; v3 reverts to a workspace-scoped endpoint with the
 * peer passed in the body as {@code observer} (the translation happens
 * in {@link HonchoV3Client#scheduleDream} before this provider runs).
 */
class DreamsProviderV3Test {

    @Test
    void metadataMatchesV3Contract() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(mock(RestClient.class));
        DreamsProviderV3 provider = new DreamsProviderV3(builder);

        assertThat(provider.operations())
            .containsExactly(HonchoOperation.SCHEDULE_DREAM);
        assertThat(provider.supportedVersions())
            .containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.SCHEDULE_DREAM))
            .isEqualTo("workspaces/{ws}/schedule_dream");
        assertThat(provider.httpMethod(HonchoOperation.SCHEDULE_DREAM))
            .isEqualTo(HttpMethod.POST);
    }
}
