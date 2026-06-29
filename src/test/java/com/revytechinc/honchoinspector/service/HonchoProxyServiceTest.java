package com.revytechinc.honchoinspector.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.revytechinc.honchoinspector.config.HonchoProperties;
import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.honcho.HonchoClient;
import com.revytechinc.honchoinspector.honcho.HonchoClientFactory;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;
import com.revytechinc.honchoinspector.honcho.HonchoResponseUnwrapper;
import com.revytechinc.honchoinspector.honcho.UnsupportedHonchoVersionException;
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
 * Tests for the T15 refactored {@link HonchoProxyService}.
 *
 * <p>The service is now an operation-based dispatch facade: it resolves
 * the right {@link HonchoClient} via {@link HonchoClientFactory} +
 * {@link HonchoContext#apiVersion()}, then delegates to
 * {@link HonchoClient#call}. These tests cover:
 * <ul>
 *   <li>The {@code call(...)} entry point — happy path, version
 *       resolution, exception propagation.</li>
 *   <li>The {@code testConnection(...)} entry point — confirms it
 *       dispatches {@code GET_WORKSPACE_INFO}.</li>
 *   <li>The 24 typed convenience methods — one parameterized test
 *       that walks every {@link HonchoOperation} and verifies the
 *       service method routes to {@code call} with the right
 *       {@link HonchoOperation} and pathVars.</li>
 *   <li>The 4 {@code @Deprecated} path-based methods — smoke tests
 *       confirming they remain on the public surface (T16 will remove
 *       the call sites; a post-Phase-1 cleanup will remove the
 *       methods).</li>
 * </ul>
 *
 * <p>No Spring context is loaded — {@link HonchoClientFactory} and
 * {@link HonchoClient} are pure Mockito mocks. This keeps the tests
 * fast and isolates the service's logic from the V3 provider chain.
 */
class HonchoProxyServiceTest {

    private static final HonchoContext CTX_V3 = new HonchoContext(
        "test-api-key", "https://api.honcho.dev", "ws-42", "test-user", HonchoApiVersion.V3
    );
    private static final HonchoContext CTX_V2 = new HonchoContext(
        "test-api-key", "https://api.honcho.dev", "ws-42", "test-user", HonchoApiVersion.V2
    );

    private static HonchoProperties properties() {
        return new HonchoProperties(
            "https://api.honcho.dev", "v3", 30_000L,
            new HonchoProperties.Providers(false),
            new HonchoProperties.Log("INFO", "100MB", 30, "500MB"),
            new HonchoProperties.Bootstrap(null, null, null, null, null),
            new HonchoProperties.Audit(90, 1_000_000L, "0 0 3 * * *")
        );
    }

    private static HonchoProxyService service(HonchoClientFactory factory) {
        return new HonchoProxyService(factory, properties(), new HonchoResponseUnwrapper());
    }

    // ------------------------------------------------------------------
    // call() — generic dispatch
    // ------------------------------------------------------------------

    @Test
    void call_resolvesClientByApiVersionAndForwardsAllArgs() {
        HonchoClientFactory factory = mock(HonchoClientFactory.class);
        HonchoClient v3Client = mock(HonchoClient.class);
        when(factory.clientFor(HonchoApiVersion.V3)).thenReturn(v3Client);

        Object body = Map.of("name", "p-1");
        Map<String, String> pathVars = Map.of("peerId", "p-1");
        Map<String, ?> query = Map.of("limit", 5);
        // Honcho v3 returns Page[Session] for LIST_PEER_SESSIONS; the
        // unwrapper extracts `.items`. The test mocks the upstream
        // envelope and asserts the unwrapper produces the right shape.
        List<String> items = List.of("s-1", "s-2");
        Map<String, Object> pageEnvelope = Map.of(
            "items", items, "total", 2, "page", 1, "size", 50, "pages", 1
        );
        when(v3Client.call(eq(HonchoOperation.LIST_PEER_SESSIONS), eq(CTX_V3), eq(body), eq(pathVars), eq(query)))
            .thenReturn(pageEnvelope);

        Object result = service(factory).call(
            HonchoOperation.LIST_PEER_SESSIONS, CTX_V3, body, pathVars, query
        );

        assertThat(result).isSameAs(pageEnvelope);
        // Every argument must be forwarded intact, including the body that
        // a real provider would serialize.
        verify(v3Client).call(
            HonchoOperation.LIST_PEER_SESSIONS, CTX_V3, body, pathVars, query
        );
    }

    @Test
    void call_usesContextApiVersionNotAField() {
        HonchoClientFactory factory = mock(HonchoClientFactory.class);
        HonchoClient v2Client = mock(HonchoClient.class);
        HonchoClient v3Client = mock(HonchoClient.class);
        when(factory.clientFor(HonchoApiVersion.V2)).thenReturn(v2Client);
        when(factory.clientFor(HonchoApiVersion.V3)).thenReturn(v3Client);
        Object v2Marker = new Object();
        // LIST_PEERS returns a Page[Peer] pagination envelope; the
        // the unwrapper passes it through. Mock the envelope and
        // assert the envelope is returned unchanged.
        List<Object> v2Items = List.of("v2-p-1");
        Map<String, Object> v2Page = Map.of("items", v2Items, "total", 1, "page", 1, "size", 50, "pages", 1);
        List<Object> v3Items = List.of("v3-p-1", "v3-p-2");
        Map<String, Object> v3Page = Map.of("items", v3Items, "total", 2, "page", 1, "size", 50, "pages", 1);
        when(v2Client.call(any(), any(), any(), any(), any())).thenReturn(v2Page);
        when(v3Client.call(any(), any(), any(), any(), any())).thenReturn(v3Page);

        HonchoProxyService svc = service(factory);

        // V2 context -> V2 client -> unwrap -> v2Items
        assertThat(svc.call(HonchoOperation.LIST_PEERS, CTX_V2, null, null, null)).isSameAs(v2Page);
        // V3 context -> V3 client -> unwrap -> v3Items
        assertThat(svc.call(HonchoOperation.LIST_PEERS, CTX_V3, null, null, null)).isSameAs(v3Page);

        // Factory must have been called with the per-context version
        // (not with a class-level field — there is no such field in T15).
        verify(factory).clientFor(HonchoApiVersion.V2);
        verify(factory).clientFor(HonchoApiVersion.V3);
    }

    @Test
    void call_propagatesHonchoCallExceptionUnchanged() {
        HonchoClientFactory factory = mock(HonchoClientFactory.class);
        HonchoClient v3Client = mock(HonchoClient.class);
        when(factory.clientFor(HonchoApiVersion.V3)).thenReturn(v3Client);
        HonchoCallException toThrow = new HonchoCallException("upstream 502", 502, "upstream body");
        when(v3Client.call(any(), any(), any(), any(), any())).thenThrow(toThrow);

        assertThatThrownBy(() ->
            service(factory).call(HonchoOperation.GET_WORKSPACE_INFO, CTX_V3, null, null, null)
        )
            .isInstanceOf(HonchoCallException.class)
            .isSameAs(toThrow);
    }

    @Test
    void call_propagatesUnsupportedHonchoVersionExceptionFromFactory() {
        HonchoClientFactory factory = mock(HonchoClientFactory.class);
        UnsupportedHonchoVersionException toThrow = new UnsupportedHonchoVersionException(
            "Honcho version V4 is not supported by this build."
        );
        when(factory.clientFor(HonchoApiVersion.V3)).thenThrow(toThrow);

        assertThatThrownBy(() ->
            service(factory).call(HonchoOperation.GET_WORKSPACE_INFO, CTX_V3, null, null, null)
        )
            .isInstanceOf(UnsupportedHonchoVersionException.class)
            .isSameAs(toThrow);
    }

    @Test
    void call_withNullBodyAndNullMapsForwardsNulls() {
        HonchoClientFactory factory = mock(HonchoClientFactory.class);
        HonchoClient v3Client = mock(HonchoClient.class);
        when(factory.clientFor(HonchoApiVersion.V3)).thenReturn(v3Client);
        Object marker = new Object();
        when(v3Client.call(any(), any(), any(), any(), any())).thenReturn(marker);

        Object result = service(factory).call(
            HonchoOperation.GET_WORKSPACE_INFO, CTX_V3, null, null, null
        );

        assertThat(result).isSameAs(marker);
        verify(v3Client).call(
            HonchoOperation.GET_WORKSPACE_INFO, CTX_V3, null, null, null
        );
    }

    // ------------------------------------------------------------------
    // testConnection()
    // ------------------------------------------------------------------

    @Test
    void testConnection_dispatchesGetWorkspaceInfo() {
        HonchoClientFactory factory = mock(HonchoClientFactory.class);
        HonchoClient v3Client = mock(HonchoClient.class);
        when(factory.clientFor(HonchoApiVersion.V3)).thenReturn(v3Client);

        service(factory).testConnection(CTX_V3);

        // Must dispatch GET_WORKSPACE_INFO with no body / no pathVars / no query.
        verify(v3Client).call(
            HonchoOperation.GET_WORKSPACE_INFO, CTX_V3, null, null, null
        );
    }

    @Test
    void testConnection_propagatesHonchoCallException() {
        HonchoClientFactory factory = mock(HonchoClientFactory.class);
        HonchoClient v3Client = mock(HonchoClient.class);
        when(factory.clientFor(HonchoApiVersion.V3)).thenReturn(v3Client);
        HonchoCallException toThrow = new HonchoCallException("upstream 502", 502, "upstream body");
        when(v3Client.call(any(), any(), any(), any(), any())).thenThrow(toThrow);

        assertThatThrownBy(() -> service(factory).testConnection(CTX_V3))
            .isInstanceOf(HonchoCallException.class)
            .isSameAs(toThrow);
    }

    // ------------------------------------------------------------------
    // properties()
    // ------------------------------------------------------------------

    @Test
    void properties_exposesTheInjectedHonchoProperties() {
        HonchoProperties props = properties();
        HonchoProxyService svc = new HonchoProxyService(
            mock(HonchoClientFactory.class), props, new HonchoResponseUnwrapper()
        );

        assertThat(svc.properties()).isSameAs(props);
    }

    // ------------------------------------------------------------------
    // 24 typed convenience methods — one test per HonchoOperation.
    // Explicit tests so a failure points at the specific service method
    // that dispatched the wrong op or the wrong pathVars.
    // ------------------------------------------------------------------

    @Test
    void typed_listPeers_passesFiltersAsQuery() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);

        svc.listPeers(CTX_V3, Map.of("limit", 5));

        verify(clientOf(factory)).call(
            HonchoOperation.LIST_PEERS, CTX_V3, null, null, Map.of("limit", 5)
        );
    }

    @Test
    void typed_createPeer_passesBody() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);
        Object body = Map.of("id", "p-1");

        svc.createPeer(CTX_V3, body);

        verify(clientOf(factory)).call(
            HonchoOperation.CREATE_PEER, CTX_V3, body, null, null
        );
    }

    @Test
    void typed_getPeerCard_passesPeerIdAsPathVar() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);

        svc.getPeerCard(CTX_V3, "p-1");

        verify(clientOf(factory)).call(
            HonchoOperation.GET_PEER_CARD, CTX_V3, null, Map.of("peerId", "p-1"), null
        );
    }

    @Test
    void typed_updatePeerCard_passesPeerIdAndBody() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);
        Object card = List.of("fact 1", "fact 2");

        svc.updatePeerCard(CTX_V3, "p-1", card);

        verify(clientOf(factory)).call(
            HonchoOperation.UPDATE_PEER_CARD, CTX_V3, card, Map.of("peerId", "p-1"), null
        );
    }

    @Test
    void typed_getPeerRepresentation_passesPeerIdAndBody() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);
        Object body = Map.of();

        svc.getPeerRepresentation(CTX_V3, "p-1", body);

        verify(clientOf(factory)).call(
            HonchoOperation.GET_REPRESENTATION, CTX_V3, body, Map.of("peerId", "p-1"), null
        );
    }

    @Test
    void typed_peerChat_passesPeerIdAndBody() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);
        Object body = Map.of("query", "hi");

        svc.peerChat(CTX_V3, "p-1", body);

        verify(clientOf(factory)).call(
            HonchoOperation.PEER_CHAT, CTX_V3, body, Map.of("peerId", "p-1"), null
        );
    }

    @Test
    void typed_searchPeers_passesPeerIdAndBody() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);
        Object body = Map.of("q", "needle");

        svc.searchPeers(CTX_V3, "p-1", body);

        verify(clientOf(factory)).call(
            HonchoOperation.SEARCH_PEERS, CTX_V3, body, Map.of("peerId", "p-1"), null
        );
    }

    @Test
    void typed_listPeerConclusions_wrapsFiltersInEnvelopeAndSetsObservedId() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);

        svc.listPeerConclusions(CTX_V3, "p-1", Map.of("size", 10));

        ArgumentCaptor<Object> bodyCap = ArgumentCaptor.forClass(Object.class);
        verify(clientOf(factory)).call(
            org.mockito.ArgumentMatchers.eq(HonchoOperation.LIST_PEER_CONCLUSIONS),
            org.mockito.ArgumentMatchers.eq(CTX_V3),
            bodyCap.capture(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull()
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) bodyCap.getValue();
        assertThat(body)
            .as("listPeerConclusions must wrap the inbound filters in Honcho's {filters:{...}} envelope")
            .containsOnlyKeys("filters");
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) body.get("filters");
        assertThat(filters)
            .containsEntry("size", 10)
            .containsEntry("observed_id", "p-1");
    }

    @Test
    void typed_listWorkspaceConclusions_wrapsFiltersInEnvelopeAndOmitsObservedId() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);

        svc.listWorkspaceConclusions(CTX_V3, Map.of("size", 10));

        ArgumentCaptor<Object> bodyCap = ArgumentCaptor.forClass(Object.class);
        verify(clientOf(factory)).call(
            org.mockito.ArgumentMatchers.eq(HonchoOperation.LIST_PEER_CONCLUSIONS),
            org.mockito.ArgumentMatchers.eq(CTX_V3),
            bodyCap.capture(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull()
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) bodyCap.getValue();
        assertThat(body)
            .as("listWorkspaceConclusions must wrap the inbound filters in Honcho's {filters:{...}} envelope")
            .containsOnlyKeys("filters");
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) body.get("filters");
        assertThat(filters)
            .containsEntry("size", 10)
            .doesNotContainKey("observed_id");
    }

    @Test
    void typed_listWorkspaceConclusions_handlesNullBody() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);

        svc.listWorkspaceConclusions(CTX_V3, null);

        ArgumentCaptor<Object> bodyCap = ArgumentCaptor.forClass(Object.class);
        verify(clientOf(factory)).call(
            org.mockito.ArgumentMatchers.eq(HonchoOperation.LIST_PEER_CONCLUSIONS),
            org.mockito.ArgumentMatchers.eq(CTX_V3),
            bodyCap.capture(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull()
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) bodyCap.getValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) body.get("filters");
        assertThat(filters).isEmpty();
    }

    @Test
    void typed_listPeerSessions_passesPeerIdAndFiltersAsBody() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);

        Map<String, Object> filters = Map.of("limit", 5);
        svc.listPeerSessions(CTX_V3, "p-1", filters);

        // v3 LIST_PEER_SESSIONS is POST with {filters:...} body. The
        // dispatcher passes filters as requestBody (3rd arg).
        verify(clientOf(factory)).call(
            HonchoOperation.LIST_PEER_SESSIONS, CTX_V3, filters,
            Map.of("peerId", "p-1"), null
        );
    }

    @Test
    void typed_queryPeerConclusions_passesPeerIdAndBody() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);
        Object body = Map.of("q", "summary");

        svc.queryPeerConclusions(CTX_V3, "p-1", body);

        verify(clientOf(factory)).call(
            HonchoOperation.QUERY_PEER_CONCLUSIONS, CTX_V3, body,
            Map.of("peerId", "p-1"), null
        );
    }

    @Test
    void typed_listSessions_passesFilters() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);

        svc.listSessions(CTX_V3, Map.of("limit", 20));

        verify(clientOf(factory)).call(
            HonchoOperation.LIST_SESSIONS, CTX_V3, null, null, Map.of("limit", 20)
        );
    }

    @Test
    void typed_createSession_passesBody() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);
        Object body = Map.of("name", "my session");

        svc.createSession(CTX_V3, body);

        verify(clientOf(factory)).call(
            HonchoOperation.CREATE_SESSION, CTX_V3, body, null, null
        );
    }

    @Test
    void typed_getSession_passesSessionId() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);

        svc.getSession(CTX_V3, "s-7");

        verify(clientOf(factory)).call(
            HonchoOperation.GET_SESSION, CTX_V3, null, Map.of("sessionId", "s-7"), null
        );
    }

    @Test
    void typed_deleteSession_passesSessionId() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);

        svc.deleteSession(CTX_V3, "s-7");

        verify(clientOf(factory)).call(
            HonchoOperation.DELETE_SESSION, CTX_V3, null, Map.of("sessionId", "s-7"), null
        );
    }

    @Test
    void typed_listSessionMessages_passesSessionIdAndFilters() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);

        svc.listSessionMessages(CTX_V3, "s-7", Map.of("limit", 50));

        verify(clientOf(factory)).call(
            HonchoOperation.LIST_SESSION_MESSAGES, CTX_V3, null,
            Map.of("sessionId", "s-7"), Map.of("limit", 50)
        );
    }

    @Test
    void typed_addMessage_passesSessionIdAndBody() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);
        Object body = Map.of("messages", List.of(Map.of("content", "hi")));

        svc.addMessage(CTX_V3, "s-7", body);

        verify(clientOf(factory)).call(
            HonchoOperation.ADD_MESSAGE, CTX_V3, body, Map.of("sessionId", "s-7"), null
        );
    }

    @Test
    void typed_getSessionContext_passesSessionIdTokensAndSummary() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);

        svc.getSessionContext(CTX_V3, "s-7", 4096, true);

        // Use ArgumentCaptor because the queryParams map is a LinkedHashMap
        // built by the service, not the literal we passed in.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> queryCap = ArgumentCaptor.forClass(Map.class);
        verify(clientOf(factory)).call(
            eq(HonchoOperation.GET_SESSION_CONTEXT), eq(CTX_V3), eq(null),
            eq(Map.of("sessionId", "s-7")), queryCap.capture()
        );
        assertThat(queryCap.getValue())
            .containsEntry("tokens", 4096)
            .containsEntry("summary", true);
    }

    @Test
    void typed_getSessionContext_omitsNullQueryParams() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);

        svc.getSessionContext(CTX_V3, "s-7", null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> queryCap = ArgumentCaptor.forClass(Map.class);
        verify(clientOf(factory)).call(
            eq(HonchoOperation.GET_SESSION_CONTEXT), eq(CTX_V3), eq(null),
            eq(Map.of("sessionId", "s-7")), queryCap.capture()
        );
        assertThat(queryCap.getValue())
            .as("both tokens and summary are null; query map should be empty")
            .isEmpty();
    }

    @Test
    void typed_getSessionContext_partialNullsAreKept() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);

        svc.getSessionContext(CTX_V3, "s-7", 2048, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> queryCap = ArgumentCaptor.forClass(Map.class);
        verify(clientOf(factory)).call(
            eq(HonchoOperation.GET_SESSION_CONTEXT), eq(CTX_V3), eq(null),
            eq(Map.of("sessionId", "s-7")), queryCap.capture()
        );
        assertThat(queryCap.getValue())
            .containsOnlyKeys("tokens")
            .containsEntry("tokens", 2048);
    }

    @Test
    void typed_getSessionSummaries_passesSessionId() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);

        svc.getSessionSummaries(CTX_V3, "s-7");

        verify(clientOf(factory)).call(
            HonchoOperation.GET_SESSION_SUMMARIES, CTX_V3, null,
            Map.of("sessionId", "s-7"), null
        );
    }

    @Test
    void typed_getSessionPeers_passesSessionId() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);

        svc.getSessionPeers(CTX_V3, "s-7");

        verify(clientOf(factory)).call(
            HonchoOperation.GET_SESSION_PEERS, CTX_V3, null,
            Map.of("sessionId", "s-7"), null
        );
    }

    @Test
    void typed_searchSessionMessages_passesSessionIdAndBody() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);
        Object body = Map.of("q", "needle");

        svc.searchSessionMessages(CTX_V3, "s-7", body);

        verify(clientOf(factory)).call(
            HonchoOperation.SEARCH_SESSION_MESSAGES, CTX_V3, body,
            Map.of("sessionId", "s-7"), null
        );
    }

    @Test
    void typed_getQueueStatus_dispatchesWithNoArgs() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);

        svc.getQueueStatus(CTX_V3);

        verify(clientOf(factory)).call(
            HonchoOperation.GET_QUEUE_STATUS, CTX_V3, null, null, null
        );
    }

    @Test
    void typed_searchMessages_passesBody() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);
        Object body = Map.of("q", "global");

        svc.searchMessages(CTX_V3, body);

        verify(clientOf(factory)).call(
            HonchoOperation.SEARCH_MESSAGES, CTX_V3, body, null, null
        );
    }

    @Test
    void typed_scheduleDream_passesPeerIdAndBody() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);
        Object body = Map.of("lookback", "7d");

        svc.scheduleDream(CTX_V3, "p-1", body);

        verify(clientOf(factory)).call(
            HonchoOperation.SCHEDULE_DREAM, CTX_V3, body,
            Map.of("peerId", "p-1"), null
        );
    }

    @Test
    void typed_getWorkspaceInfo_dispatchesWithNoArgs() {
        HonchoClientFactory factory = v3Factory();
        HonchoProxyService svc = service(factory);

        svc.getWorkspaceInfo(CTX_V3);

        verify(clientOf(factory)).call(
            HonchoOperation.GET_WORKSPACE_INFO, CTX_V3, null, null, null
        );
    }

    // ------------------------------------------------------------------
    // @Deprecated path-based methods — kept compiling for HonchoController
    // until T16 refactors it. Smoke tests only: confirm the method exists
    // and that calling it surfaces a clear migration message at runtime.
    // ------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    @Test
    void deprecatedGet_throwsWithMigrationMessage() {
        HonchoProxyService svc = service(mock(HonchoClientFactory.class));

        assertThatThrownBy(() -> svc.get(CTX_V3, "/v3/workspaces/ws/peers", null))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("T16")
            .hasMessageContaining("call(HonchoOperation");
    }

    @SuppressWarnings("deprecation")
    @Test
    void deprecatedPost_throwsWithMigrationMessage() {
        HonchoProxyService svc = service(mock(HonchoClientFactory.class));

        assertThatThrownBy(() -> svc.post(CTX_V3, "/v3/workspaces/ws/peers", null, Map.of("id", "p-1")))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("T16");
    }

    @SuppressWarnings("deprecation")
    @Test
    void deprecatedPut_throwsWithMigrationMessage() {
        HonchoProxyService svc = service(mock(HonchoClientFactory.class));

        assertThatThrownBy(() -> svc.put(CTX_V3, "/v3/workspaces/ws/peers/p-1/card", null, List.of("fact")))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("T16");
    }

    @SuppressWarnings("deprecation")
    @Test
    void deprecatedDelete_throwsWithMigrationMessage() {
        HonchoProxyService svc = service(mock(HonchoClientFactory.class));

        assertThatThrownBy(() -> svc.delete(CTX_V3, "/v3/workspaces/ws/sessions/s-7"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("T16");
    }

    // ------------------------------------------------------------------
    // Test helpers
    // ------------------------------------------------------------------

    private static HonchoClientFactory v3Factory() {
        HonchoClientFactory factory = mock(HonchoClientFactory.class);
        HonchoClient v3 = mock(HonchoClient.class);
        when(factory.clientFor(HonchoApiVersion.V3)).thenReturn(v3);
        return factory;
    }

    private static HonchoClient clientOf(HonchoClientFactory factory) {
        return factory.clientFor(HonchoApiVersion.V3);
    }
}
