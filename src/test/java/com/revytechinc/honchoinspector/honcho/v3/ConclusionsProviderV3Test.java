package com.revytechinc.honchoinspector.honcho.v3;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;
import com.revytechinc.honchoinspector.model.HonchoContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Contract test for {@link ConclusionsProviderV3}. Three tests covering the
 * v3 contract for {@link HonchoOperation#LIST_PEER_CONCLUSIONS}:
 * <ol>
 *   <li>{@code operations()} advertises the op; {@code supportedVersions()}
 *       returns exactly {@code {V3}}.</li>
 *   <li>{@code pathTemplate(op)} + {@code httpMethod(op)} match Honcho v3:
 *       POST {@code /v3/workspaces/{ws}/conclusions/list}.</li>
 *   <li>URL construction produces the right absolute URL and
 *       {@link ConclusionsProviderV3#buildFiltersBody} wraps a flat filter
 *       map in Honcho's {@code {filters: {...}}} envelope.</li>
 * </ol>
 */
class ConclusionsProviderV3Test {

    private static final HonchoContext CTX = new HonchoContext(
        "test-api-key", "https://api.honcho.dev", "ws-42", "test-user"
    );

    @Test
    void listPeerConclusions_advertisesV3PostOnConclusionsListEndpoint() {
        ConclusionsProviderV3 provider = newProvider();

        assertThat(provider.operations())
            .as("LIST_PEER_CONCLUSIONS moved out of PeerQueryProviderV3 into ConclusionsProviderV3")
            .contains(HonchoOperation.LIST_PEER_CONCLUSIONS);
        assertThat(provider.supportedVersions())
            .as("ConclusionsProviderV3 is v3-only — no other version should be claimed")
            .containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.LIST_PEER_CONCLUSIONS))
            .isEqualTo("workspaces/{ws}/conclusions/list");
        assertThat(provider.httpMethod(HonchoOperation.LIST_PEER_CONCLUSIONS))
            .as("Honcho v3 changed the verb from GET (per-peer) to POST (workspace-level)")
            .isEqualTo(HttpMethod.POST);
        assertThat(urlFor())
            .as("URL omits {peerId} because the upstream endpoint is workspace-level")
            .isEqualTo("https://api.honcho.dev/v3/workspaces/ws-42/conclusions/list");
    }

    @Test
    void buildFiltersBody_wrapsFlatFiltersInEnvelope() {
        Map<String, Object> body = ConclusionsProviderV3.buildFiltersBody(
            Map.of("size", 10),
            null,
            Map.of("peerId", "p-99")
        );
        assertThat(body).containsOnlyKeys("filters");
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) body.get("filters");
        assertThat(filters)
            .containsEntry("size", 10)
            .containsEntry("observed_id", "p-99");
    }

    @Test
    void buildFiltersBody_passesPreWrappedBodyThroughVerbatim() {
        Map<String, Object> preWrapped = Map.of(
            "filters", Map.of("observer_id", "p-1", "size", 5)
        );
        Map<String, Object> body = ConclusionsProviderV3.buildFiltersBody(
            preWrapped, null, Map.of("peerId", "p-99")
        );
        assertThat(body).isSameAs(preWrapped);
    }

    @Test
    void buildFiltersBody_handlesAbsentFiltersGracefully() {
        Map<String, Object> body = ConclusionsProviderV3.buildFiltersBody(
            null, null, null
        );
        assertThat(body).containsOnlyKeys("filters");
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) body.get("filters");
        assertThat(filters).isEmpty();
    }

    private static ConclusionsProviderV3 newProvider() {
        RestClient mockClient = mock(RestClient.class);
        return new ConclusionsProviderV3(mockClient);
    }

    private static String urlFor() {
        ConclusionsProviderV3 provider = newProvider();
        String template = provider.pathTemplate(HonchoOperation.LIST_PEER_CONCLUSIONS);
        String substituted = V3ProviderSupport.substitutePath(template, CTX, null);
        return V3ProviderSupport.buildUrl(CTX.baseUrl(), CTX.apiVersion().pathPrefix(), substituted, Map.of());
    }
}
