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
 * Contract test for {@link SearchProviderV3}: the single-op provider
 * that handles {@code POST /v3/workspaces/{ws}/search}. The v2
 * legacy endpoint was GET with query params; v3 changed it to POST
 * with a JSON body.
 */
class SearchProviderV3Test {

    @Test
    void metadataMatchesV3Contract() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(mock(RestClient.class));
        SearchProviderV3 provider = new SearchProviderV3(builder);

        assertThat(provider.operations())
            .containsExactly(HonchoOperation.SEARCH_MESSAGES);
        assertThat(provider.supportedVersions())
            .containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.SEARCH_MESSAGES))
            .isEqualTo("workspaces/{ws}/search");
        assertThat(provider.httpMethod(HonchoOperation.SEARCH_MESSAGES))
            .isEqualTo(HttpMethod.POST);
    }
}
