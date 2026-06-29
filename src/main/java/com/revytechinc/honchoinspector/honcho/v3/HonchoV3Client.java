package com.revytechinc.honchoinspector.honcho.v3;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.honcho.HonchoClient;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;
import com.revytechinc.honchoinspector.honcho.HonchoProvider;
import com.revytechinc.honchoinspector.honcho.HonchoProviderRegistry;
import com.revytechinc.honchoinspector.model.HonchoContext;

/**
 * Honcho v3 implementation of the {@link HonchoClient} surface.
 *
 * <p>This class is a thin dispatcher: it owns no transport, no URL
 * construction, and no auth headers. Each of the 24 methods
 * (<em>one per {@link HonchoOperation}</em>) does exactly four things:
 * <ol>
 *   <li>Builds a {@code Map<String, String> pathVars} carrying the path
 *       placeholders the operation needs (e.g. {@code peerId},
 *       {@code sessionId}), pulled from the method's own arguments.</li>
 *   <li>Builds a {@code Map<String, ?> queryParams} from the {@code filters}
 *       argument (only for methods that accept filters; {@code null}
 *       everywhere else).</li>
 *   <li>Looks up the {@link HonchoProvider} responsible for that op via
 *       the per-instance {@link HonchoProviderRegistry}.</li>
 *   <li>Calls
 *       {@code provider.execute(op, ctx, this, requestBody, pathVars, queryParams)}
 *       and returns its result unchanged.</li>
 * </ol>
 *
 * <p>Because every dispatch is one line, the 24 method bodies are visually
 * identical except for which {@link HonchoOperation} constant they
 * reference and which arguments they pull into {@code pathVars}. Keeping
 * the body uniform means the test suite can assert a small invariant
 * (right op, right provider, right pathVars keys, right body, right
 * queryParams) for every method.
 *
 * <h2>Spring auto-discovery</h2>
 * The constructor receives {@code List<HonchoProvider>} — Spring injects
 * every {@code @Component} that implements {@link HonchoProvider} across
 * the application. The registry filters to {@link HonchoApiVersion#V3}
 * providers internally, so a future V4 (or V2) provider can be added
 * without touching this class. {@link HonchoClientFactory} (T8) indexes
 * this bean by {@link #supportedVersions()}, which returns
 * {@code EnumSet.of(V3)}; that index is what {@code HonchoProxyService}
 * (T15) will use to look us up.
 *
 * <h2>Why no path/body/header construction lives here</h2>
 * URL building, {@code RestClient} dispatch, and auth-header injection
 * all live in the per-resource {@link HonchoProvider} classes
 * (e.g. {@code PeersProviderV3}, {@code SessionsProviderV3}). Keeping
 * this class purely as a {@code HonchoClient \u2192 HonchoOperation}
 * dispatcher means the provider layer can evolve (new HTTP client, new
 * auth scheme, new Honcho version) without rippling back into the
 * client interface or the controller layer above.
 */
@Component
public class HonchoV3Client implements HonchoClient {

    private final HonchoProviderRegistry registry;

    /**
     * Build the V3 client from every {@link HonchoProvider} bean Spring
     * discovered. The registry filters the list to providers that claim
     * {@link HonchoApiVersion#V3} support internally; the caller does
     * not need to pre-filter.
     *
     * @param allProviders every {@code HonchoProvider} bean in the
     *                      application context; may be empty but must
     *                      be non-null.
     */
    public HonchoV3Client(List<HonchoProvider> allProviders) {
        this.registry = new HonchoProviderRegistry(HonchoApiVersion.V3, allProviders);
        this.registry.validateFullCoverage();
    }

    @Override
    public Set<HonchoApiVersion> supportedVersions() {
        return EnumSet.of(HonchoApiVersion.V3);
    }

    // ------------------------------------------------------------------
    // Peers resource
    // ------------------------------------------------------------------

    @Override
    public Object listPeers(HonchoContext ctx, Map<String, ?> filters) throws HonchoCallException {
        return call(HonchoOperation.LIST_PEERS, ctx, filters, null, null);
    }

    @Override
    public Object createPeer(HonchoContext ctx, Object createPeerRequest) throws HonchoCallException {
        return call(HonchoOperation.CREATE_PEER, ctx, createPeerRequest, null, null);
    }

    @Override
    public Object updatePeer(HonchoContext ctx, String peerId, Object updatePeerRequest) throws HonchoCallException {
        return call(HonchoOperation.UPDATE_PEER, ctx, updatePeerRequest, pathVars("peerId", peerId), null);
    }

    @Override
    public Object getPeerCard(HonchoContext ctx, String peerId) throws HonchoCallException {
        return call(HonchoOperation.GET_PEER_CARD, ctx, null, pathVars("peerId", peerId), null);
    }

    @Override
    public Object updatePeerCard(HonchoContext ctx, String peerId, Object cardData) throws HonchoCallException {
        return call(HonchoOperation.UPDATE_PEER_CARD, ctx, cardData, pathVars("peerId", peerId), null);
    }

    @Override
    public Object getPeerRepresentation(HonchoContext ctx, String peerId, Object body) throws HonchoCallException {
        return call(HonchoOperation.GET_REPRESENTATION, ctx, body, pathVars("peerId", peerId), null);
    }

    // ------------------------------------------------------------------
    // Peer query resource (chat, search, conclusions, sessions)
    // ------------------------------------------------------------------

    @Override
    public Object peerChat(HonchoContext ctx, String peerId, Object chatRequest) throws HonchoCallException {
        return call(HonchoOperation.PEER_CHAT, ctx, chatRequest, pathVars("peerId", peerId), null);
    }

    @Override
    public Object searchPeers(HonchoContext ctx, String peerId, Object searchRequest) throws HonchoCallException {
        return call(HonchoOperation.SEARCH_PEERS, ctx, searchRequest, pathVars("peerId", peerId), null);
    }

    @Override
    public Object listPeerConclusions(HonchoContext ctx, String peerId, Object body) throws HonchoCallException {
        return call(HonchoOperation.LIST_PEER_CONCLUSIONS, ctx, body, null, null);
    }

    @Override
    public Object listPeerSessions(HonchoContext ctx, String peerId, Map<String, ?> filters) throws HonchoCallException {
        return call(HonchoOperation.LIST_PEER_SESSIONS, ctx, filters, pathVars("peerId", peerId), null);
    }

    @Override
    public Object queryPeerConclusions(HonchoContext ctx, String peerId, Object queryRequest) throws HonchoCallException {
        return call(HonchoOperation.QUERY_PEER_CONCLUSIONS, ctx, queryRequest, pathVars("peerId", peerId), null);
    }

    // ------------------------------------------------------------------
    // Sessions resource
    // ------------------------------------------------------------------

    @Override
    public Object listSessions(HonchoContext ctx, Map<String, ?> filters) throws HonchoCallException {
        return call(HonchoOperation.LIST_SESSIONS, ctx, filters, null, null);
    }

    @Override
    public Object createSession(HonchoContext ctx, Object createSessionRequest) throws HonchoCallException {
        return call(HonchoOperation.CREATE_SESSION, ctx, createSessionRequest, null, null);
    }

    @Override
    public Object getSession(HonchoContext ctx, String sessionId) throws HonchoCallException {
        return call(HonchoOperation.GET_SESSION, ctx, null, pathVars("sessionId", sessionId), null);
    }

    @Override
    public Object deleteSession(HonchoContext ctx, String sessionId) throws HonchoCallException {
        return call(HonchoOperation.DELETE_SESSION, ctx, null, pathVars("sessionId", sessionId), null);
    }

    @Override
    public Object updateSession(HonchoContext ctx, String sessionId, Object updateSessionRequest) throws HonchoCallException {
        return call(HonchoOperation.UPDATE_SESSION, ctx, updateSessionRequest, pathVars("sessionId", sessionId), null);
    }

    @Override
    public Object getSessionContext(HonchoContext ctx, String sessionId, Integer tokens, Boolean summary) throws HonchoCallException {
        return call(HonchoOperation.GET_SESSION_CONTEXT, ctx, null, pathVars("sessionId", sessionId), contextQueryParams(tokens, summary));
    }

    @Override
    public Object getSessionSummaries(HonchoContext ctx, String sessionId) throws HonchoCallException {
        return call(HonchoOperation.GET_SESSION_SUMMARIES, ctx, null, pathVars("sessionId", sessionId), null);
    }

    @Override
    public Object getSessionPeers(HonchoContext ctx, String sessionId) throws HonchoCallException {
        return call(HonchoOperation.GET_SESSION_PEERS, ctx, null, pathVars("sessionId", sessionId), null);
    }

    // ------------------------------------------------------------------
    // Session messages resource
    // ------------------------------------------------------------------

    @Override
    public Object listSessionMessages(HonchoContext ctx, String sessionId, Map<String, ?> filters) throws HonchoCallException {
        return call(HonchoOperation.LIST_SESSION_MESSAGES, ctx, filters, pathVars("sessionId", sessionId), null);
    }

    @Override
    public Object addMessage(HonchoContext ctx, String sessionId, Object messageRequest) throws HonchoCallException {
        return call(HonchoOperation.ADD_MESSAGE, ctx, messageRequest, pathVars("sessionId", sessionId), null);
    }

    @Override
    public Object updateMessage(HonchoContext ctx, String sessionId, String messageId, Object updateMessageRequest) throws HonchoCallException {
        return call(
            HonchoOperation.UPDATE_MESSAGE,
            ctx,
            updateMessageRequest,
            pathVars("sessionId", sessionId, "messageId", messageId),
            null
        );
    }

    @Override
    public Object searchSessionMessages(HonchoContext ctx, String sessionId, Object searchRequest) throws HonchoCallException {
        return call(HonchoOperation.SEARCH_SESSION_MESSAGES, ctx, searchRequest, pathVars("sessionId", sessionId), null);
    }

    // ------------------------------------------------------------------
    // Workspace-level operations
    // ------------------------------------------------------------------

    @Override
    public Object getQueueStatus(HonchoContext ctx) throws HonchoCallException {
        return call(HonchoOperation.GET_QUEUE_STATUS, ctx, null, null, null);
    }

    @Override
    public Object searchMessages(HonchoContext ctx, Object searchRequest) throws HonchoCallException {
        return call(HonchoOperation.SEARCH_MESSAGES, ctx, searchRequest, null, null);
    }

    @Override
    public Object scheduleDream(HonchoContext ctx, String peerId, Object dreamRequest) throws HonchoCallException {
        return call(HonchoOperation.SCHEDULE_DREAM, ctx, dreamRequest, pathVars("peerId", peerId), null);
    }

    @Override
    public Object getWorkspaceInfo(HonchoContext ctx) throws HonchoCallException {
        return call(HonchoOperation.GET_WORKSPACE_INFO, ctx, null, null, null);
    }

    // ------------------------------------------------------------------
    // Workspace-level conclusions write surface (create batch + delete one)
    // ------------------------------------------------------------------

    @Override
    public Object createConclusions(HonchoContext ctx, Object conclusionsBatch) throws HonchoCallException {
        // Provider handles envelope wrapping (defaults to {conclusions:[]}
        // when the caller passes null). The proxy passes the body through
        // verbatim so the UI can supply its own filtered batch.
        return call(HonchoOperation.CREATE_CONCLUSIONS, ctx, conclusionsBatch, null, null);
    }

    @Override
    public Object deleteConclusion(HonchoContext ctx, String conclusionId) throws HonchoCallException {
        // Honcho v3 returns 204 No Content on delete. The provider's
        // execute() does NOT receive a body for DELETE operations,
        // so the dispatcher returns null; the proxy service normalizes
        // that null into a {data: null} envelope per ResponseEnvelopeAdvice.
        return call(HonchoOperation.DELETE_CONCLUSION, ctx, null, pathVars("conclusionId", conclusionId), null);
    }

// ------------------------------------------------------------------
    // Dispatch core
    // ------------------------------------------------------------------

    /**
     * Look up the provider for {@code op} in the registry and call its
     * {@link HonchoProvider#execute(HonchoOperation, HonchoContext,
     * HonchoClient, Object, Map, Map) execute} method with the given
     * arguments, returning the provider's result unchanged.
     *
     * <p>The {@code this} reference passed to {@code execute} is the
     * {@link HonchoClient} instance — the provider can use it to
     * reach back into the client surface (e.g. for retries, logging)
     * if it ever needs to. None of the eight v3 providers do, but the
     * interface reserves the option.
     *
     * <p>{@link HonchoCallException} and {@link IllegalStateException}
     * (from the registry's "no provider covers this op" branch) are
     * both declared by {@link HonchoProvider#execute}, so callers see
     * them as runtime exceptions just as the provider raised them.
     *
     * <p><strong>T15 design choice:</strong> the 24 typed convenience
     * methods above all delegate here with the right {@link HonchoOperation}
     * constant + pathVars + queryParams built from the typed arguments.
     * External callers (e.g. {@code HonchoProxyService}) that need to
     * forward an operation they don't have a typed method for can call
     * this method directly. Keeping the dispatch logic in exactly one
     * place ensures the typed and untyped paths cannot diverge.
     */
    @Override
    public Object call(
        HonchoOperation op,
        HonchoContext ctx,
        Object requestBody,
        Map<String, String> pathVars,
        Map<String, ?> queryParams
    ) throws HonchoCallException {
        String peerId = pathVars == null ? null : pathVars.get("peerId");
        boolean putPeer = peerId != null && !peerId.isBlank();
        try {
            if (putPeer) MDC.put(MDC_PEER_ID, peerId);
            HonchoProvider provider = registry.get(op);
            return provider.execute(op, ctx, this, requestBody, pathVars, queryParams);
        } finally {
            if (putPeer) MDC.remove(MDC_PEER_ID);
        }
    }

    public static final String MDC_PEER_ID = "peer_id";

    /**
     * Build a pathVars map from alternating key/value pairs. Centralised
     * so the 24 dispatch methods stay one-liners and so a future change
     * to the pathVars shape (e.g. switching to {@code Map.of} with a
     * null check) lives in one place. Supports one or two path
     * placeholders — the latter is needed by
     * {@link HonchoOperation#UPDATE_MESSAGE}
     * ({@code sessions/{sessionId}/messages/{messageId}}).
     */
    private static Map<String, String> pathVars(String k1, String v1) {
        return Map.of(k1, v1);
    }

    private static Map<String, String> pathVars(String k1, String v1, String k2, String v2) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    /**
     * Build the query-parameter map for {@code getSessionContext}, where
     * the two optional parameters ({@code tokens}, {@code summary}) are
     * passed as a flat map. {@code null} values are filtered out so the
     * resulting query string omits the key entirely.
     */
    private static Map<String, ?> contextQueryParams(Integer tokens, Boolean summary) {
        Map<String, Object> q = new java.util.LinkedHashMap<>();
        if (tokens != null) {
            q.put("tokens", tokens);
        }
        if (summary != null) {
            q.put("summary", summary);
        }
        return q;
    }
}
