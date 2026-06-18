package com.revytechinc.honchoinspector.honcho.v3;

import java.net.URI;
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
 * Contract test for {@link PeerQueryProviderV3}. Five tests, one per
 * operation the provider owns. Each test verifies the four things the
 * registry will rely on for that op, plus the absolute URL that the
 * package-private {@code substitutePath} + {@code buildUri} helpers
 * produce:
 * <ol>
 *   <li>{@code operations()} advertises the op under test.</li>
 *   <li>{@code supportedVersions()} returns exactly {@code {V3}}.</li>
 *   <li>{@code pathTemplate(op)} returns the right v3 template.</li>
 *   <li>{@code httpMethod(op)} returns the right verb (the two LIST
 *       endpoints are GET; chat, search, and the conclusions query are
 *       POST).</li>
 *   <li>URL construction produces the right absolute URL for the supplied
 *       {@code {ws}} and {@code {peerId}} placeholders.</li>
 * </ol>
 */
class PeerQueryProviderV3Test {

    private static final HonchoContext CTX = new HonchoContext(
        "test-api-key", "https://api.honcho.dev", "ws-42", "test-user"
    );

    private static final Map<String, String> PEER_PATH_VARS = Map.of(
        "peerId", "p-99"
    );

    @Test
    void peerChat_isDeclaredAsPostOnChatEndpoint() {
        PeerQueryProviderV3 provider = newProvider();

        assertThat(provider.operations())
            .as("PEER_CHAT must be in operations()")
            .contains(HonchoOperation.PEER_CHAT);
        assertThat(provider.supportedVersions())
            .as("PeerQueryProviderV3 is v3-only")
            .containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.PEER_CHAT))
            .isEqualTo("v3/workspaces/{ws}/peers/{peerId}/chat");
        assertThat(provider.httpMethod(HonchoOperation.PEER_CHAT))
            .isEqualTo(HttpMethod.POST);
        assertThat(urlFor(HonchoOperation.PEER_CHAT, PEER_PATH_VARS))
            .as("URL must include both {ws} and {peerId} placeholders and the /chat suffix")
            .hasToString("https://api.honcho.dev/v3/workspaces/ws-42/peers/p-99/chat");
    }

    @Test
    void searchPeers_isDeclaredAsPostOnPeerSearchEndpoint() {
        PeerQueryProviderV3 provider = newProvider();

        assertThat(provider.operations())
            .contains(HonchoOperation.SEARCH_PEERS);
        assertThat(provider.supportedVersions())
            .containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.SEARCH_PEERS))
            .isEqualTo("v3/workspaces/{ws}/peers/{peerId}/search");
        assertThat(provider.httpMethod(HonchoOperation.SEARCH_PEERS))
            .isEqualTo(HttpMethod.POST);
        assertThat(urlFor(HonchoOperation.SEARCH_PEERS, PEER_PATH_VARS))
            .as("URL must include both {ws} and {peerId} placeholders and the /search suffix")
            .hasToString("https://api.honcho.dev/v3/workspaces/ws-42/peers/p-99/search");
    }

    @Test
    void listPeerConclusions_isDeclaredAsGetOnConclusionsEndpoint() {
        PeerQueryProviderV3 provider = newProvider();

        assertThat(provider.operations())
            .contains(HonchoOperation.LIST_PEER_CONCLUSIONS);
        assertThat(provider.supportedVersions())
            .containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.LIST_PEER_CONCLUSIONS))
            .isEqualTo("v3/workspaces/{ws}/peers/{peerId}/conclusions");
        assertThat(provider.httpMethod(HonchoOperation.LIST_PEER_CONCLUSIONS))
            .isEqualTo(HttpMethod.GET);
        assertThat(urlFor(HonchoOperation.LIST_PEER_CONCLUSIONS, PEER_PATH_VARS))
            .as("URL must include both {ws} and {peerId} placeholders and the /conclusions suffix")
            .hasToString("https://api.honcho.dev/v3/workspaces/ws-42/peers/p-99/conclusions");
    }

    @Test
    void listPeerSessions_isDeclaredAsGetOnSessionsEndpoint() {
        PeerQueryProviderV3 provider = newProvider();

        assertThat(provider.operations())
            .contains(HonchoOperation.LIST_PEER_SESSIONS);
        assertThat(provider.supportedVersions())
            .containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.LIST_PEER_SESSIONS))
            .isEqualTo("v3/workspaces/{ws}/peers/{peerId}/sessions");
        assertThat(provider.httpMethod(HonchoOperation.LIST_PEER_SESSIONS))
            .isEqualTo(HttpMethod.GET);
        assertThat(urlFor(HonchoOperation.LIST_PEER_SESSIONS, PEER_PATH_VARS))
            .as("URL must include both {ws} and {peerId} placeholders and the /sessions suffix")
            .hasToString("https://api.honcho.dev/v3/workspaces/ws-42/peers/p-99/sessions");
    }

    @Test
    void queryPeerConclusions_isDeclaredAsPostOnConclusionsQueryEndpoint() {
        PeerQueryProviderV3 provider = newProvider();

        assertThat(provider.operations())
            .contains(HonchoOperation.QUERY_PEER_CONCLUSIONS);
        assertThat(provider.supportedVersions())
            .containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.QUERY_PEER_CONCLUSIONS))
            .isEqualTo("v3/workspaces/{ws}/peers/{peerId}/conclusions/query");
        assertThat(provider.httpMethod(HonchoOperation.QUERY_PEER_CONCLUSIONS))
            .isEqualTo(HttpMethod.POST);
        assertThat(urlFor(HonchoOperation.QUERY_PEER_CONCLUSIONS, PEER_PATH_VARS))
            .as("URL must include both {ws} and {peerId} placeholders and the /conclusions/query suffix")
            .hasToString("https://api.honcho.dev/v3/workspaces/ws-42/peers/p-99/conclusions/query");
    }

    private static PeerQueryProviderV3 newProvider() {
        RestClient mockClient = mock(RestClient.class);
        return new PeerQueryProviderV3(mockClient);
    }

    private static URI urlFor(HonchoOperation op, Map<String, String> pathVars) {
        PeerQueryProviderV3 provider = newProvider();
        String template = provider.pathTemplate(op);
        String substituted = PeerQueryProviderV3.substitutePath(template, CTX, pathVars);
        return PeerQueryProviderV3.buildUri(CTX, substituted, Map.of());
    }
}
