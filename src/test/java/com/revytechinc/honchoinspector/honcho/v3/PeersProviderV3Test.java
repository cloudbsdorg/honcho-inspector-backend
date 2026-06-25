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
 * Contract test for {@link PeersProviderV3}. Five tests, one per operation
 * the provider owns. Each test verifies the four things the registry will
 * rely on for that op, plus the absolute URL that
 * {@link V3ProviderSupport#substitutePath} +
 * {@link V3ProviderSupport#buildUrl} produce:
 * <ol>
 *   <li>{@code operations()} advertises the op under test.</li>
 *   <li>{@code supportedVersions()} returns exactly {@code {V3}}.</li>
 *   <li>{@code pathTemplate(op)} returns the right v3 template (including
 *       the {@code v2 \u2192 v3} GET\u2192POST change for {@link HonchoOperation#LIST_PEERS}).</li>
 *   <li>{@code httpMethod(op)} returns the right verb.</li>
 *   <li>URL construction produces the right absolute URL for the supplied
 *       {@code {ws}} and {@code {peerId}} placeholders.</li>
 * </ol>
 */
class PeersProviderV3Test {

    private static final HonchoContext CTX = new HonchoContext(
        "test-api-key", "https://api.honcho.dev", "ws-42", "test-user"
    );

    private static final Map<String, String> PEER_PATH_VARS = Map.of(
        "peerId", "p-99"
    );

    @Test
    void listPeers_isDeclaredAsPostOnPeersListEndpoint() {
        PeersProviderV3 provider = newProvider();

        assertThat(provider.operations())
            .as("LIST_PEERS must be in operations()")
            .contains(HonchoOperation.LIST_PEERS);
        assertThat(provider.supportedVersions())
            .as("PeersProviderV3 is v3-only")
            .containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.LIST_PEERS))
            .as("LIST_PEERS is the workspace peers collection; v3 promoted it to POST /peers/list")
            .isEqualTo("workspaces/{ws}/peers/list");
        assertThat(provider.httpMethod(HonchoOperation.LIST_PEERS))
            .as("LIST_PEERS is POST in v3 (was GET in v2)")
            .isEqualTo(HttpMethod.POST);
        assertThat(urlFor(HonchoOperation.LIST_PEERS, Map.of()))
            .as("URL must include the ws placeholder and the /peers/list suffix")
            .isEqualTo("https://api.honcho.dev/v3/workspaces/ws-42/peers/list");
    }

    @Test
    void createPeer_isDeclaredAsPostOnPeersEndpoint() {
        PeersProviderV3 provider = newProvider();

        assertThat(provider.operations())
            .contains(HonchoOperation.CREATE_PEER);
        assertThat(provider.supportedVersions())
            .containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.CREATE_PEER))
            .isEqualTo("workspaces/{ws}/peers");
        assertThat(provider.httpMethod(HonchoOperation.CREATE_PEER))
            .isEqualTo(HttpMethod.POST);
        assertThat(urlFor(HonchoOperation.CREATE_PEER, Map.of()))
            .as("URL must include the ws placeholder; no /list suffix on create")
            .isEqualTo("https://api.honcho.dev/v3/workspaces/ws-42/peers");
    }

    @Test
    void getPeerCard_isDeclaredAsGetOnPeerCardEndpoint() {
        PeersProviderV3 provider = newProvider();

        assertThat(provider.operations())
            .contains(HonchoOperation.GET_PEER_CARD);
        assertThat(provider.supportedVersions())
            .containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.GET_PEER_CARD))
            .isEqualTo("workspaces/{ws}/peers/{peerId}/card");
        assertThat(provider.httpMethod(HonchoOperation.GET_PEER_CARD))
            .isEqualTo(HttpMethod.GET);
        assertThat(urlFor(HonchoOperation.GET_PEER_CARD, PEER_PATH_VARS))
            .as("URL must include both {ws} and {peerId} placeholders")
            .isEqualTo("https://api.honcho.dev/v3/workspaces/ws-42/peers/p-99/card");
    }

    @Test
    void updatePeerCard_isDeclaredAsPutOnPeerCardEndpoint() {
        PeersProviderV3 provider = newProvider();

        assertThat(provider.operations())
            .contains(HonchoOperation.UPDATE_PEER_CARD);
        assertThat(provider.supportedVersions())
            .containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.UPDATE_PEER_CARD))
            .isEqualTo("workspaces/{ws}/peers/{peerId}/card");
        assertThat(provider.httpMethod(HonchoOperation.UPDATE_PEER_CARD))
            .isEqualTo(HttpMethod.PUT);
        assertThat(urlFor(HonchoOperation.UPDATE_PEER_CARD, PEER_PATH_VARS))
            .as("URL must include both {ws} and {peerId} placeholders")
            .isEqualTo("https://api.honcho.dev/v3/workspaces/ws-42/peers/p-99/card");
    }

    @Test
    void getRepresentation_isDeclaredAsPostOnRepresentationEndpoint() {
        PeersProviderV3 provider = newProvider();

        assertThat(provider.operations())
            .contains(HonchoOperation.GET_REPRESENTATION);
        assertThat(provider.supportedVersions())
            .containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.GET_REPRESENTATION))
            .isEqualTo("workspaces/{ws}/peers/{peerId}/representation");
        assertThat(provider.httpMethod(HonchoOperation.GET_REPRESENTATION))
            .as("Honcho v3 disallows GET on /representation (returns 405); the proxy uses POST with {} body")
            .isEqualTo(HttpMethod.POST);
        assertThat(urlFor(HonchoOperation.GET_REPRESENTATION, PEER_PATH_VARS))
            .as("URL must include the {peerId} placeholder and the /representation suffix")
            .isEqualTo("https://api.honcho.dev/v3/workspaces/ws-42/peers/p-99/representation");
    }

    private static PeersProviderV3 newProvider() {
        RestClient mockClient = mock(RestClient.class);
        return new PeersProviderV3(mockClient);
    }

    private static String urlFor(HonchoOperation op, Map<String, String> pathVars) {
        PeersProviderV3 provider = newProvider();
        String template = provider.pathTemplate(op);
        String substituted = V3ProviderSupport.substitutePath(template, CTX, pathVars);
        return V3ProviderSupport.buildUrl(CTX.baseUrl(), CTX.apiVersion().pathPrefix(), substituted, Map.of());
    }
}
