package com.revytechinc.honchoinspector.honcho.v3;

import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;
import com.revytechinc.honchoinspector.honcho.HonchoProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Structural unit tests for {@link PeersProviderV3} and
 * {@link PeerQueryProviderV3}. Ten per-op tests (5 + 5) plus one
 * parameterized aggregator asserting every peer-cluster provider is
 * v3-only and advertises at least one operation.
 *
 * <p>Each per-op test verifies the four metadata points the
 * {@code HonchoProviderRegistry} relies on:
 * <ol>
 *   <li>{@code operations()} advertises the operation.</li>
 *   <li>{@code supportedVersions()} returns exactly {@code {V3}}.</li>
 *   <li>{@code pathTemplate(op)} returns the expected v3 path template.</li>
 *   <li>{@code httpMethod(op)} returns the expected HTTP verb.</li>
 * </ol>
 *
 * <p>T27 is structural-only by design: it does NOT call {@code execute()}
 * or exercise HTTP. Behaviour integration tests live in T10's
 * {@code PeersProviderV3Test} / {@code PeerQueryProviderV3Test} and
 * T14's {@code HonchoV3ClientTest}; end-to-end workflow tests live in
 * T26's {@code HonchoWorkflowIntegrationTest}.
 */
class PeersProviderV3UnitTest {

    private static PeersProviderV3 newPeersProvider() {
        return new PeersProviderV3(mock(RestClient.class));
    }

    private static PeerQueryProviderV3 newPeerQueryProvider() {
        return new PeerQueryProviderV3(mock(RestClient.class));
    }

    private static Stream<HonchoProvider> peerClusterProviders() {
        return Stream.of(newPeersProvider(), newPeerQueryProvider());
    }

    @Test
    void listPeers_advertisesV3PostOnListEndpoint() {
        PeersProviderV3 provider = newPeersProvider();

        assertThat(provider.operations())
            .as("LIST_PEERS must be in PeersProviderV3.operations()")
            .contains(HonchoOperation.LIST_PEERS);
        assertThat(provider.supportedVersions())
            .as("PeersProviderV3 is v3-only — no other version should be claimed")
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.LIST_PEERS))
            .isEqualTo("v3/workspaces/{ws}/peers/list");
        assertThat(provider.httpMethod(HonchoOperation.LIST_PEERS))
            .as("v2→v3 contract change: LIST_PEERS is now POST")
            .isEqualTo(HttpMethod.POST);
    }

    @Test
    void createPeer_advertisesV3PostOnPeersEndpoint() {
        PeersProviderV3 provider = newPeersProvider();

        assertThat(provider.operations()).contains(HonchoOperation.CREATE_PEER);
        assertThat(provider.supportedVersions())
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.CREATE_PEER))
            .isEqualTo("v3/workspaces/{ws}/peers");
        assertThat(provider.httpMethod(HonchoOperation.CREATE_PEER))
            .isEqualTo(HttpMethod.POST);
    }

    @Test
    void getPeerCard_advertisesV3GetOnCardEndpoint() {
        PeersProviderV3 provider = newPeersProvider();

        assertThat(provider.operations()).contains(HonchoOperation.GET_PEER_CARD);
        assertThat(provider.supportedVersions())
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.GET_PEER_CARD))
            .isEqualTo("v3/workspaces/{ws}/peers/{peerId}/card");
        assertThat(provider.httpMethod(HonchoOperation.GET_PEER_CARD))
            .isEqualTo(HttpMethod.GET);
    }

    @Test
    void updatePeerCard_advertisesV3PostOnCardEndpoint() {
        PeersProviderV3 provider = newPeersProvider();

        assertThat(provider.operations()).contains(HonchoOperation.UPDATE_PEER_CARD);
        assertThat(provider.supportedVersions())
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.UPDATE_PEER_CARD))
            .isEqualTo("v3/workspaces/{ws}/peers/{peerId}/card");
        assertThat(provider.httpMethod(HonchoOperation.UPDATE_PEER_CARD))
            .isEqualTo(HttpMethod.POST);
    }

    @Test
    void getRepresentation_advertisesV3GetOnRepresentationEndpoint() {
        PeersProviderV3 provider = newPeersProvider();

        assertThat(provider.operations()).contains(HonchoOperation.GET_REPRESENTATION);
        assertThat(provider.supportedVersions())
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.GET_REPRESENTATION))
            .isEqualTo("v3/workspaces/{ws}/peers/{peerId}/representation");
        assertThat(provider.httpMethod(HonchoOperation.GET_REPRESENTATION))
            .isEqualTo(HttpMethod.GET);
    }

    @Test
    void peerChat_advertisesV3PostOnChatEndpoint() {
        PeerQueryProviderV3 provider = newPeerQueryProvider();

        assertThat(provider.operations()).contains(HonchoOperation.PEER_CHAT);
        assertThat(provider.supportedVersions())
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.PEER_CHAT))
            .isEqualTo("v3/workspaces/{ws}/peers/{peerId}/chat");
        assertThat(provider.httpMethod(HonchoOperation.PEER_CHAT))
            .isEqualTo(HttpMethod.POST);
    }

    @Test
    void searchPeers_advertisesV3PostOnPeerSearchEndpoint() {
        PeerQueryProviderV3 provider = newPeerQueryProvider();

        assertThat(provider.operations()).contains(HonchoOperation.SEARCH_PEERS);
        assertThat(provider.supportedVersions())
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.SEARCH_PEERS))
            .isEqualTo("v3/workspaces/{ws}/peers/{peerId}/search");
        assertThat(provider.httpMethod(HonchoOperation.SEARCH_PEERS))
            .isEqualTo(HttpMethod.POST);
    }

    @Test
    void listPeerConclusions_advertisesV3GetOnConclusionsEndpoint() {
        PeerQueryProviderV3 provider = newPeerQueryProvider();

        assertThat(provider.operations()).contains(HonchoOperation.LIST_PEER_CONCLUSIONS);
        assertThat(provider.supportedVersions())
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.LIST_PEER_CONCLUSIONS))
            .isEqualTo("v3/workspaces/{ws}/peers/{peerId}/conclusions");
        assertThat(provider.httpMethod(HonchoOperation.LIST_PEER_CONCLUSIONS))
            .isEqualTo(HttpMethod.GET);
    }

    @Test
    void listPeerSessions_advertisesV3GetOnSessionsEndpoint() {
        PeerQueryProviderV3 provider = newPeerQueryProvider();

        assertThat(provider.operations()).contains(HonchoOperation.LIST_PEER_SESSIONS);
        assertThat(provider.supportedVersions())
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.LIST_PEER_SESSIONS))
            .isEqualTo("v3/workspaces/{ws}/peers/{peerId}/sessions");
        assertThat(provider.httpMethod(HonchoOperation.LIST_PEER_SESSIONS))
            .isEqualTo(HttpMethod.GET);
    }

    @Test
    void queryPeerConclusions_advertisesV3PostOnConclusionsQueryEndpoint() {
        PeerQueryProviderV3 provider = newPeerQueryProvider();

        assertThat(provider.operations()).contains(HonchoOperation.QUERY_PEER_CONCLUSIONS);
        assertThat(provider.supportedVersions())
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.QUERY_PEER_CONCLUSIONS))
            .isEqualTo("v3/workspaces/{ws}/peers/{peerId}/conclusions/query");
        assertThat(provider.httpMethod(HonchoOperation.QUERY_PEER_CONCLUSIONS))
            .isEqualTo(HttpMethod.POST);
    }

    @ParameterizedTest
    @MethodSource("peerClusterProviders")
    void everyPeerClusterProvider_isV3OnlyAndAdvertisesAtLeastOneOperation(HonchoProvider provider) {
        assertThat(provider.supportedVersions())
            .as("Peer-cluster providers must be v3-only")
            .containsExactly(HonchoApiVersion.V3);
        assertThat(provider.operations())
            .as("Peer-cluster providers must advertise at least one operation")
            .isNotEmpty();
    }
}
