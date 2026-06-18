package com.revytechinc.honchoinspector.honcho.v3;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Structural unit tests for the four single-op V3 providers introduced
 * in T13: {@link WorkspaceProviderV3}, {@link QueueStatusProviderV3},
 * {@link SearchProviderV3}, and {@link DreamsProviderV3}. Four
 * {@code metadataMatchesV3Contract} tests (one per provider).
 *
 * <p>Each test verifies the four metadata points the
 * {@code HonchoProviderRegistry} relies on:
 * <ol>
 *   <li>{@code operations()} advertises exactly the one operation the
 *       provider owns.</li>
 *   <li>{@code supportedVersions()} returns exactly {@code {V3}}.</li>
 *   <li>{@code pathTemplate(op)} returns the expected workspace-relative
 *       v3 path template (the dispatcher prepends the v3 prefix and
 *       workspace segment at call time).</li>
 *   <li>{@code httpMethod(op)} returns the expected HTTP verb.</li>
 * </ol>
 *
 * <p>Each provider's constructor takes a {@code RestClient.Builder}; the
 * builder is mocked to return a mock {@code RestClient} on {@code build()}
 * so the provider can be instantiated without touching a network.
 *
 * <p>T27 is structural-only by design: it does NOT call {@code execute()}
 * or exercise HTTP. Behaviour integration tests live in T13's
 * {@code WorkspaceProviderV3Test}, {@code QueueStatusProviderV3Test},
 * {@code SearchProviderV3Test}, and {@code DreamsProviderV3Test}; the
 * composite {@code /workspace/info} endpoint test lives in T26's
 * {@code HonchoWorkflowIntegrationTest}.
 */
class MiscProvidersV3UnitTest {

    @Test
    void workspaceProvider_advertisesGetOnWorkspacesEndpoint() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(mock(RestClient.class));
        WorkspaceProviderV3 provider = new WorkspaceProviderV3(builder);

        assertThat(provider.operations())
            .as("WorkspaceProviderV3 advertises exactly GET_WORKSPACE_INFO")
            .containsExactly(HonchoOperation.GET_WORKSPACE_INFO);
        assertThat(provider.supportedVersions())
            .as("WorkspaceProviderV3 is v3-only")
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.GET_WORKSPACE_INFO))
            .isEqualTo("workspaces/{ws}");
        assertThat(provider.httpMethod(HonchoOperation.GET_WORKSPACE_INFO))
            .isEqualTo(HttpMethod.GET);
    }

    @Test
    void queueStatusProvider_advertisesGetOnQueueStatusEndpoint() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(mock(RestClient.class));
        QueueStatusProviderV3 provider = new QueueStatusProviderV3(builder);

        assertThat(provider.operations())
            .as("QueueStatusProviderV3 advertises exactly GET_QUEUE_STATUS")
            .containsExactly(HonchoOperation.GET_QUEUE_STATUS);
        assertThat(provider.supportedVersions())
            .as("QueueStatusProviderV3 is v3-only")
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.GET_QUEUE_STATUS))
            .isEqualTo("workspaces/{ws}/queue-status");
        assertThat(provider.httpMethod(HonchoOperation.GET_QUEUE_STATUS))
            .isEqualTo(HttpMethod.GET);
    }

    @Test
    void searchProvider_advertisesPostOnSearchEndpoint() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(mock(RestClient.class));
        SearchProviderV3 provider = new SearchProviderV3(builder);

        assertThat(provider.operations())
            .as("SearchProviderV3 advertises exactly SEARCH_MESSAGES")
            .containsExactly(HonchoOperation.SEARCH_MESSAGES);
        assertThat(provider.supportedVersions())
            .as("SearchProviderV3 is v3-only")
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.SEARCH_MESSAGES))
            .isEqualTo("workspaces/{ws}/search");
        assertThat(provider.httpMethod(HonchoOperation.SEARCH_MESSAGES))
            .as("v2→v3 contract change: SEARCH_MESSAGES is now POST (was GET)")
            .isEqualTo(HttpMethod.POST);
    }

    @Test
    void dreamsProvider_advertisesPostOnDreamsEndpoint() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(mock(RestClient.class));
        DreamsProviderV3 provider = new DreamsProviderV3(builder);

        assertThat(provider.operations())
            .as("DreamsProviderV3 advertises exactly SCHEDULE_DREAM")
            .containsExactly(HonchoOperation.SCHEDULE_DREAM);
        assertThat(provider.supportedVersions())
            .as("DreamsProviderV3 is v3-only")
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.SCHEDULE_DREAM))
            .isEqualTo("workspaces/{ws}/peers/{peerId}/dreams");
        assertThat(provider.httpMethod(HonchoOperation.SCHEDULE_DREAM))
            .isEqualTo(HttpMethod.POST);
    }
}
