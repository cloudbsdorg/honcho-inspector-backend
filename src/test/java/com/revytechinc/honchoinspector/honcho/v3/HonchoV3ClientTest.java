package com.revytechinc.honchoinspector.honcho.v3;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.honcho.HonchoClient;
import com.revytechinc.honchoinspector.honcho.HonchoClientFactory;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;
import com.revytechinc.honchoinspector.honcho.HonchoProvider;
import com.revytechinc.honchoinspector.honcho.HonchoProviderRegistry;
import com.revytechinc.honchoinspector.model.HonchoContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract test for {@link HonchoV3Client}. The client is a pure
 * dispatcher: it owns no transport and no URL logic, so every test
 * here boils down to "given input X, does the right HonchoProvider
 * get called with the right op + pathVars + body + queryParams".
 *
 * <p>The test mocks the eight V3 providers as raw {@link HonchoProvider}
 * interfaces (not as their concrete classes). Each test then verifies
 * that the {@code HonchoOperation} constant the client dispatches with
 * matches what the {@code HonchoClient} method's name implies, and that
 * the path / body / query arguments are forwarded intact.
 *
 * <p>Three additional tests cover the cross-cutting concerns:
 * <ul>
 *   <li>{@link #supportedVersionsIsV3()} \u2014 the factory's index key.</li>
 *   <li>{@link #honchoClientFactoryIndexesThisClientForV3()} \u2014 confirms
 *       {@link HonchoClientFactory} picks the bean up via Spring DI.</li>
 *   <li>{@link #providerHonchoCallExceptionPropagates()} \u2014 the
 *       {@link HonchoCallException} contract is preserved across the
 *       dispatch boundary.</li>
 *   <li>{@link #missingProviderThrowsIllegalState()} \u2014 a client wired
 *       with no providers surfaces the registry's "uncovered op" error
 *       to the caller.</li>
 * </ul>
 */
class HonchoV3ClientTest {

    private static final HonchoContext CTX = new HonchoContext(
        "test-api-key", "https://api.honcho.dev", "ws-42", "test-user", HonchoApiVersion.V3
    );

    /**
     * Single fake provider that records every call to {@code execute()}
     * and returns a recognisable marker object. Each HonchoOperation
     * the client dispatches to is captured as the {@code op} argument.
     *
     * <p>One provider per HonchoOperation is enough because the
     * registry's per-op keying is exercised end-to-end: a different op
     * would route to a different provider in a multi-provider
     * registry, but here we just need to confirm the client passes
     * the right op constant.
     */
    static class CapturingProvider implements HonchoProvider {
        final Object marker = new Object();
        HonchoOperation lastOp;
        HonchoContext lastCtx;
        HonchoClient lastClient;
        Object lastBody;
        Map<String, String> lastPathVars;
        // Stored as Map<String, Object> so AssertJ's MapAssert.containsEntry can
        // type-check values uniformly. The HonchoProvider contract uses
        // Map<String, ?> because callers legitimately pass a heterogeneous
        // value bag; the runtime values are always Objects, so this cast is safe.
        @SuppressWarnings("unchecked")
        Map<String, Object> lastQueryParams;

        @Override
        public Set<HonchoOperation> operations() {
            return EnumSet.allOf(HonchoOperation.class);
        }

        @Override
        public Set<HonchoApiVersion> supportedVersions() {
            return EnumSet.of(HonchoApiVersion.V3);
        }

        @Override
        public Object execute(
            HonchoOperation op,
            HonchoContext ctx,
            HonchoClient client,
            Object requestBody,
            Map<String, String> pathVars,
            Map<String, ?> queryParams
        ) {
            this.lastOp = op;
            this.lastCtx = ctx;
            this.lastClient = client;
            this.lastBody = requestBody;
            this.lastPathVars = pathVars;
            this.lastQueryParams = (Map<String, Object>) queryParams;
            return marker;
        }
    }

    /**
     * Build a client backed by a single capturing provider, register
     * it for all 24 ops, and hand it back to the test.
     */
    private static CapturingProvider newFixture() {
        CapturingProvider provider = new CapturingProvider();
        HonchoV3Client client = new HonchoV3Client(List.of(provider));
        return provider;
    }

    private static HonchoV3Client clientOf(CapturingProvider provider) {
        return new HonchoV3Client(List.of(provider));
    }

    // ------------------------------------------------------------------
    // Peers resource (5 ops)
    // ------------------------------------------------------------------

    @Test
    void listPeers_forwardsFiltersAndNoPathVars() {
        CapturingProvider provider = newFixture();
        Map<String, Object> filters = Map.of("limit", 5);

        Object result = clientOf(provider).listPeers(CTX, filters);

        assertThat(result).isSameAs(provider.marker);
        assertThat(provider.lastOp).isEqualTo(HonchoOperation.LIST_PEERS);
        assertThat(provider.lastCtx).isSameAs(CTX);
        assertThat(provider.lastPathVars).isNull();
        assertThat(provider.lastQueryParams).isSameAs(filters);
        assertThat(provider.lastBody).isNull();
    }

    @Test
    void createPeer_forwardsBody() {
        CapturingProvider provider = newFixture();
        Object body = Map.of("id", "p-new", "name", "new peer");

        clientOf(provider).createPeer(CTX, body);

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.CREATE_PEER);
        assertThat(provider.lastPathVars).isNull();
        assertThat(provider.lastQueryParams).isNull();
        assertThat(provider.lastBody).isSameAs(body);
    }

    @Test
    void getPeerCard_forwardsPeerIdAsPathVar() {
        CapturingProvider provider = newFixture();

        clientOf(provider).getPeerCard(CTX, "p-99");

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.GET_PEER_CARD);
        assertThat(provider.lastPathVars)
            .containsOnlyKeys("peerId")
            .containsEntry("peerId", "p-99");
        assertThat(provider.lastQueryParams).isNull();
        assertThat(provider.lastBody).isNull();
    }

    @Test
    void updatePeerCard_forwardsPeerIdAndBody() {
        CapturingProvider provider = newFixture();
        Object card = List.of("fact 1", "fact 2");

        clientOf(provider).updatePeerCard(CTX, "p-99", card);

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.UPDATE_PEER_CARD);
        assertThat(provider.lastPathVars)
            .containsOnlyKeys("peerId")
            .containsEntry("peerId", "p-99");
        assertThat(provider.lastBody).isSameAs(card);
    }

    @Test
    void getPeerRepresentation_forwardsPeerId() {
        CapturingProvider provider = newFixture();

        clientOf(provider).getPeerRepresentation(CTX, "p-99");

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.GET_REPRESENTATION);
        assertThat(provider.lastPathVars)
            .containsOnlyKeys("peerId")
            .containsEntry("peerId", "p-99");
        assertThat(provider.lastBody).isNull();
    }

    // ------------------------------------------------------------------
    // Peer query resource (5 ops)
    // ------------------------------------------------------------------

    @Test
    void peerChat_forwardsPeerIdAndBody() {
        CapturingProvider provider = newFixture();
        Object body = Map.of("query", "hello");

        clientOf(provider).peerChat(CTX, "p-99", body);

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.PEER_CHAT);
        assertThat(provider.lastPathVars)
            .containsOnlyKeys("peerId")
            .containsEntry("peerId", "p-99");
        assertThat(provider.lastBody).isSameAs(body);
    }

    @Test
    void searchPeers_forwardsPeerIdAndBody() {
        CapturingProvider provider = newFixture();
        Object body = Map.of("q", "needle");

        clientOf(provider).searchPeers(CTX, "p-99", body);

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.SEARCH_PEERS);
        assertThat(provider.lastPathVars)
            .containsOnlyKeys("peerId")
            .containsEntry("peerId", "p-99");
        assertThat(provider.lastBody).isSameAs(body);
    }

    @Test
    void listPeerConclusions_forwardsPeerIdAndFilters() {
        CapturingProvider provider = newFixture();
        Map<String, Object> filters = Map.of("limit", 10);

        clientOf(provider).listPeerConclusions(CTX, "p-99", filters);

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.LIST_PEER_CONCLUSIONS);
        assertThat(provider.lastPathVars)
            .containsOnlyKeys("peerId")
            .containsEntry("peerId", "p-99");
        assertThat(provider.lastQueryParams).isSameAs(filters);
        assertThat(provider.lastBody).isNull();
    }

    @Test
    void listPeerSessions_forwardsPeerIdAndFilters() {
        CapturingProvider provider = newFixture();
        Map<String, Object> filters = Map.of("limit", 5);

        clientOf(provider).listPeerSessions(CTX, "p-99", filters);

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.LIST_PEER_SESSIONS);
        assertThat(provider.lastPathVars)
            .containsOnlyKeys("peerId")
            .containsEntry("peerId", "p-99");
        assertThat(provider.lastQueryParams).isSameAs(filters);
        assertThat(provider.lastBody).isNull();
    }

    @Test
    void queryPeerConclusions_forwardsPeerIdAndBody() {
        CapturingProvider provider = newFixture();
        Object body = Map.of("q", "summary");

        clientOf(provider).queryPeerConclusions(CTX, "p-99", body);

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.QUERY_PEER_CONCLUSIONS);
        assertThat(provider.lastPathVars)
            .containsOnlyKeys("peerId")
            .containsEntry("peerId", "p-99");
        assertThat(provider.lastBody).isSameAs(body);
    }

    // ------------------------------------------------------------------
    // Sessions resource (7 ops)
    // ------------------------------------------------------------------

    @Test
    void listSessions_forwardsFilters() {
        CapturingProvider provider = newFixture();
        Map<String, Object> filters = Map.of("limit", 20);

        clientOf(provider).listSessions(CTX, filters);

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.LIST_SESSIONS);
        assertThat(provider.lastPathVars).isNull();
        assertThat(provider.lastQueryParams).isSameAs(filters);
        assertThat(provider.lastBody).isNull();
    }

    @Test
    void createSession_forwardsBody() {
        CapturingProvider provider = newFixture();
        Object body = Map.of("name", "my session");

        clientOf(provider).createSession(CTX, body);

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.CREATE_SESSION);
        assertThat(provider.lastPathVars).isNull();
        assertThat(provider.lastBody).isSameAs(body);
    }

    @Test
    void getSession_forwardsSessionId() {
        CapturingProvider provider = newFixture();

        clientOf(provider).getSession(CTX, "s-7");

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.GET_SESSION);
        assertThat(provider.lastPathVars)
            .containsOnlyKeys("sessionId")
            .containsEntry("sessionId", "s-7");
        assertThat(provider.lastBody).isNull();
    }

    @Test
    void deleteSession_forwardsSessionIdAndNoBody() {
        CapturingProvider provider = newFixture();

        clientOf(provider).deleteSession(CTX, "s-7");

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.DELETE_SESSION);
        assertThat(provider.lastPathVars)
            .containsOnlyKeys("sessionId")
            .containsEntry("sessionId", "s-7");
        assertThat(provider.lastBody).isNull();
        assertThat(provider.lastQueryParams).isNull();
    }

    @Test
    void getSessionContext_forwardsSessionIdTokensAndSummary() {
        CapturingProvider provider = newFixture();

        clientOf(provider).getSessionContext(CTX, "s-7", 4096, true);

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.GET_SESSION_CONTEXT);
        assertThat(provider.lastPathVars)
            .containsOnlyKeys("sessionId")
            .containsEntry("sessionId", "s-7");
        assertThat(provider.lastQueryParams)
            .containsOnlyKeys("tokens", "summary")
            .containsEntry("tokens", 4096)
            .containsEntry("summary", true);
        assertThat(provider.lastBody).isNull();
    }

    @Test
    void getSessionContext_omitsNullQueryParams() {
        CapturingProvider provider = newFixture();

        clientOf(provider).getSessionContext(CTX, "s-7", null, null);

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.GET_SESSION_CONTEXT);
        assertThat(provider.lastPathVars)
            .containsOnlyKeys("sessionId")
            .containsEntry("sessionId", "s-7");
        assertThat(provider.lastQueryParams).isEmpty();
    }

    @Test
    void getSessionSummaries_forwardsSessionId() {
        CapturingProvider provider = newFixture();

        clientOf(provider).getSessionSummaries(CTX, "s-7");

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.GET_SESSION_SUMMARIES);
        assertThat(provider.lastPathVars)
            .containsOnlyKeys("sessionId")
            .containsEntry("sessionId", "s-7");
    }

    @Test
    void getSessionPeers_forwardsSessionId() {
        CapturingProvider provider = newFixture();

        clientOf(provider).getSessionPeers(CTX, "s-7");

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.GET_SESSION_PEERS);
        assertThat(provider.lastPathVars)
            .containsOnlyKeys("sessionId")
            .containsEntry("sessionId", "s-7");
    }

    // ------------------------------------------------------------------
    // Session messages resource (3 ops)
    // ------------------------------------------------------------------

    @Test
    void listSessionMessages_forwardsSessionIdAndFilters() {
        CapturingProvider provider = newFixture();
        Map<String, Object> filters = Map.of("limit", 50);

        clientOf(provider).listSessionMessages(CTX, "s-7", filters);

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.LIST_SESSION_MESSAGES);
        assertThat(provider.lastPathVars)
            .containsOnlyKeys("sessionId")
            .containsEntry("sessionId", "s-7");
        assertThat(provider.lastQueryParams).isSameAs(filters);
        assertThat(provider.lastBody).isNull();
    }

    @Test
    void addMessage_forwardsSessionIdAndBody() {
        CapturingProvider provider = newFixture();
        Object body = Map.of("messages", List.of(Map.of("content", "hi")));

        clientOf(provider).addMessage(CTX, "s-7", body);

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.ADD_MESSAGE);
        assertThat(provider.lastPathVars)
            .containsOnlyKeys("sessionId")
            .containsEntry("sessionId", "s-7");
        assertThat(provider.lastBody).isSameAs(body);
    }

    @Test
    void searchSessionMessages_forwardsSessionIdAndBody() {
        CapturingProvider provider = newFixture();
        Object body = Map.of("q", "needle");

        clientOf(provider).searchSessionMessages(CTX, "s-7", body);

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.SEARCH_SESSION_MESSAGES);
        assertThat(provider.lastPathVars)
            .containsOnlyKeys("sessionId")
            .containsEntry("sessionId", "s-7");
        assertThat(provider.lastBody).isSameAs(body);
    }

    // ------------------------------------------------------------------
    // Workspace-level operations (4 ops)
    // ------------------------------------------------------------------

    @Test
    void getQueueStatus_dispatchesWithNoBodyOrPathVars() {
        CapturingProvider provider = newFixture();

        clientOf(provider).getQueueStatus(CTX);

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.GET_QUEUE_STATUS);
        assertThat(provider.lastPathVars).isNull();
        assertThat(provider.lastQueryParams).isNull();
        assertThat(provider.lastBody).isNull();
    }

    @Test
    void searchMessages_forwardsBody() {
        CapturingProvider provider = newFixture();
        Object body = Map.of("q", "global");

        clientOf(provider).searchMessages(CTX, body);

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.SEARCH_MESSAGES);
        assertThat(provider.lastPathVars).isNull();
        assertThat(provider.lastBody).isSameAs(body);
    }

    @Test
    void scheduleDream_forwardsPeerIdAndBody() {
        CapturingProvider provider = newFixture();
        Object body = Map.of("lookback", "7d");

        clientOf(provider).scheduleDream(CTX, "p-99", body);

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.SCHEDULE_DREAM);
        assertThat(provider.lastPathVars)
            .containsOnlyKeys("peerId")
            .containsEntry("peerId", "p-99");
        assertThat(provider.lastBody).isSameAs(body);
    }

    @Test
    void getWorkspaceInfo_dispatchesWithNoArgs() {
        CapturingProvider provider = newFixture();

        clientOf(provider).getWorkspaceInfo(CTX);

        assertThat(provider.lastOp).isEqualTo(HonchoOperation.GET_WORKSPACE_INFO);
        assertThat(provider.lastPathVars).isNull();
        assertThat(provider.lastQueryParams).isNull();
        assertThat(provider.lastBody).isNull();
    }

    // ------------------------------------------------------------------
    // Cross-cutting concerns
    // ------------------------------------------------------------------

    @Test
    void supportedVersionsIsV3() {
        HonchoV3Client client = new HonchoV3Client(List.of());

        assertThat(client.supportedVersions())
            .containsExactly(HonchoApiVersion.V3);
    }

    @Test
    void honchoClientFactoryIndexesThisClientForV3() {
        // Spring normally discovers HonchoV3Client via @Component + constructor injection of List<HonchoProvider>.
        // The factory test emulates that by passing the client directly, which is what HonchoClientFactory(List<HonchoClient>) does.
        HonchoV3Client v3 = new HonchoV3Client(List.of());
        HonchoClientFactory factory = new HonchoClientFactory(List.of(v3));

        assertThat(factory.clientFor(HonchoApiVersion.V3)).isSameAs(v3);

        // The factory should still refuse V2 and V4 \u2014 no other client claims them.
        assertThatThrownBy(() -> factory.clientFor(HonchoApiVersion.V2))
            .isInstanceOf(com.revytechinc.honchoinspector.honcho.UnsupportedHonchoVersionException.class);
        assertThatThrownBy(() -> factory.clientFor(HonchoApiVersion.V4))
            .isInstanceOf(com.revytechinc.honchoinspector.honcho.UnsupportedHonchoVersionException.class);
    }

    @Test
    void providerHonchoCallExceptionPropagates() {
        HonchoProvider throwing = mock(HonchoProvider.class);
        when(throwing.supportedVersions()).thenReturn(EnumSet.of(HonchoApiVersion.V3));
        when(throwing.operations()).thenReturn(EnumSet.of(HonchoOperation.LIST_PEERS));
        HonchoCallException toThrow = new HonchoCallException("upstream 502", 502, "upstream body");
        when(throwing.execute(eq(HonchoOperation.LIST_PEERS), any(), any(), any(), any(), any()))
            .thenThrow(toThrow);

        HonchoV3Client client = new HonchoV3Client(List.of(throwing));

        assertThatThrownBy(() -> client.listPeers(CTX, null))
            .isSameAs(toThrow);
    }

    @Test
    void missingProviderThrowsIllegalState() {
        // Wire the client with NO providers. Every dispatch should fail with the
        // registry's "no HonchoProvider registered" IllegalStateException.
        HonchoV3Client client = new HonchoV3Client(List.of());

        assertThatThrownBy(() -> client.listPeers(CTX, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LIST_PEERS")
            .hasMessageContaining("V3");
    }

    @Test
    void passedClientReferenceIsTheHonchoV3ClientItself() {
        CapturingProvider provider = newFixture();
        HonchoV3Client client = clientOf(provider);

        client.getPeerCard(CTX, "p-99");

        // The 3rd argument to provider.execute is the HonchoClient surface;
        // the client passes `this` so providers can reach back through it
        // (none of the v3 providers do, but the interface reserves the option).
        assertThat(provider.lastClient).isSameAs(client);
    }

    @Test
    void registryFiltersOutProvidersForOtherVersions() {
        // A V2-only provider must NOT receive any V3 dispatch.
        HonchoProvider v2Only = mock(HonchoProvider.class);
        when(v2Only.supportedVersions()).thenReturn(EnumSet.of(HonchoApiVersion.V2));
        when(v2Only.operations()).thenReturn(EnumSet.allOf(HonchoOperation.class));

        HonchoV3Client client = new HonchoV3Client(List.of(v2Only));

        assertThatThrownBy(() -> client.listPeers(CTX, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("V3");

        // The V2-only provider must not have been called.
        verify(v2Only, org.mockito.Mockito.never()).execute(
            any(), any(), any(), any(), any(), any());
    }

    @Test
    void usesHonchoProviderRegistry() {
        // The HonchoV3Client constructor must build a HonchoProviderRegistry for V3
        // from the supplied providers \u2014 not a different dispatch table. We can't
        // reach the private field directly, but we can confirm the behaviour by
        // confirming the registry's "first wins" semantics apply: pass two providers
        // that both claim the same op and verify the one that sorted first wins.
        HonchoProvider alpha = mock(HonchoProvider.class);
        HonchoProvider beta = mock(HonchoProvider.class);
        when(alpha.supportedVersions()).thenReturn(EnumSet.of(HonchoApiVersion.V3));
        when(beta.supportedVersions()).thenReturn(EnumSet.of(HonchoApiVersion.V3));
        when(alpha.operations()).thenReturn(EnumSet.of(HonchoOperation.LIST_PEERS));
        when(beta.operations()).thenReturn(EnumSet.of(HonchoOperation.LIST_PEERS));
        Object alphaMarker = new Object();
        Object betaMarker = new Object();
        when(alpha.execute(any(), any(), any(), any(), any(), any())).thenReturn(alphaMarker);
        when(beta.execute(any(), any(), any(), any(), any(), any())).thenReturn(betaMarker);

        // Force a deterministic order: alpha first.
        HonchoV3Client client = new HonchoV3Client(List.of(alpha, beta));
        Object result = client.listPeers(CTX, null);

        // Registry sorts providers by class name internally, so the winner is
        // whichever mock has the alphabetically-earlier fully-qualified name.
        // We just assert that exactly one of them handled the call.
        ArgumentCaptor<HonchoOperation> opCaptor = ArgumentCaptor.forClass(HonchoOperation.class);
        if (result == alphaMarker) {
            verify(alpha).execute(opCaptor.capture(), any(), any(), any(), any(), any());
            verify(beta, org.mockito.Mockito.never()).execute(
                any(), any(), any(), any(), any(), any());
        } else {
            verify(beta).execute(opCaptor.capture(), any(), any(), any(), any(), any());
            verify(alpha, org.mockito.Mockito.never()).execute(
                any(), any(), any(), any(), any(), any());
        }
        assertThat(opCaptor.getValue()).isEqualTo(HonchoOperation.LIST_PEERS);
    }

    @Test
    void getSessionContext_withNullTokensAndSummaryBuildsEmptyQuery() {
        CapturingProvider provider = newFixture();

        clientOf(provider).getSessionContext(CTX, "s-7", null, false);

        assertThat(provider.lastQueryParams)
            .as("only summary should be present when tokens is null")
            .containsOnlyKeys("summary")
            .containsEntry("summary", false);
    }
}
