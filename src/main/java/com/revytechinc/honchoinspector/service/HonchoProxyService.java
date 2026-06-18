package com.revytechinc.honchoinspector.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.revytechinc.honchoinspector.config.HonchoProperties;
import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.honcho.HonchoClient;
import com.revytechinc.honchoinspector.honcho.HonchoClientFactory;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;
import com.revytechinc.honchoinspector.model.HonchoContext;

/**
 * Operation-based dispatch facade in front of {@link HonchoClientFactory}
 * + the registered {@link HonchoClient} implementations.
 *
 * <p>Pre-T15, this class owned the {@code RestClient} and constructed
 * paths directly from a hard-coded {@code honcho.api-version} property.
 * That design coupled the proxy to a single Honcho API version at
 * compile time and forced every Honcho endpoint to live as a raw path
 * string inside the controller layer.
 *
 * <p>T15 replaces that with a {@link HonchoClientFactory}-driven pipeline:
 * each call resolves the right client via {@code ctx.apiVersion()} (the
 * per-profile version set in T17), then dispatches via the generic
 * {@link HonchoClient#call(HonchoOperation, HonchoContext, Object, Map, Map)}
 * entry point. The 24 typed convenience methods on this class mirror the
 * 24 methods on {@link HonchoClient} — they exist for the {@code ProfileController}
 * and other legacy callers that want a strongly-typed surface, and they
 * will be the surface the {@code HonchoController} migrates to in T16.
 *
 * <h2>Two public APIs, side by side</h2>
 * <ol>
 *   <li>The 24 typed convenience methods + {@link #call(HonchoOperation, HonchoContext, Object, Map, Map)} +
 *       {@link #testConnection(HonchoContext)} — the new operation-based surface.
 *       Stable; this is the surface future code should target.</li>
 *   <li>The 4 {@code @Deprecated} path-based methods
 *       ({@link #get}, {@link #post}, {@link #put}, {@link #delete}) — kept
 *       temporarily so the current {@code HonchoController} continues to
 *       compile. T16 deletes the call sites; a post-Phase-1 cleanup
 *       deletes the methods themselves. They are stubbed with
 *       {@link UnsupportedOperationException} because the T15 refactor
 *       intentionally removed the {@code RestClient} field — see the
 *       class Javadoc on {@code HonchoClientFactory} for the new
 *       architecture.</li>
 * </ol>
 */
@Service
public class HonchoProxyService {

    private final HonchoClientFactory factory;
    private final HonchoProperties properties;

    public HonchoProxyService(HonchoClientFactory factory, HonchoProperties properties) {
        this.factory = factory;
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // Generic dispatch (T15) — the one entry point controllers and
    // future code should target.
    // ------------------------------------------------------------------

    /**
     * Generic dispatch entry point: resolve the {@link HonchoClient}
     * for {@code ctx.apiVersion()} and delegate to its
     * {@link HonchoClient#call} method.
     *
     * <p>The version is taken from the call-site context, not from a
     * class field — this lets a single backend instance serve multiple
     * Honcho versions in parallel (one per profile) without re-reading
     * a global property on every call.
     */
    public Object call(
        HonchoOperation op,
        HonchoContext ctx,
        Object requestBody,
        Map<String, String> pathVars,
        Map<String, ?> queryParams
    ) throws HonchoCallException {
        HonchoClient client = factory.clientFor(ctx.apiVersion());
        boolean putProfile = ctx.profileId() != null;
        boolean putVersion = ctx.apiVersion() != null;
        try {
            if (putProfile) MDC.put(MDC_PROFILE_ID, ctx.profileId());
            if (putVersion) MDC.put(MDC_HONCHO_VERSION, ctx.apiVersion().pathPrefix());
            return client.call(op, ctx, requestBody, pathVars, queryParams);
        } finally {
            if (putProfile) MDC.remove(MDC_PROFILE_ID);
            if (putVersion) MDC.remove(MDC_HONCHO_VERSION);
        }
    }

    public static final String MDC_PROFILE_ID = "profile_id";
    public static final String MDC_HONCHO_VERSION = "honcho_version";

    /**
     * Probe a profile by issuing a {@code GET_WORKSPACE_INFO} call. The
     * upstream call itself is the connectivity check; any
     * {@link HonchoCallException} propagates to the caller so the
     * controller layer can render the failure.
     */
    public void testConnection(HonchoContext ctx) throws HonchoCallException {
        call(HonchoOperation.GET_WORKSPACE_INFO, ctx, null, null, null);
    }

    /**
     * Read-only access to the resolved {@link HonchoProperties}. Used by
     * the {@code StartupInfoLogger} (and any future bean that needs the
     * default Honcho upstream configuration) without re-injecting the
     * properties record.
     */
    public HonchoProperties properties() {
        return properties;
    }

    // ------------------------------------------------------------------
    // 24 typed convenience methods — one per HonchoOperation.
    // Each is a one-liner that calls call() with the right op + pathVars.
    // ------------------------------------------------------------------

    public Object listPeers(HonchoContext ctx, Map<String, ?> filters) throws HonchoCallException {
        return call(HonchoOperation.LIST_PEERS, ctx, null, null, filters);
    }

    public Object createPeer(HonchoContext ctx, Object createPeerRequest) throws HonchoCallException {
        return call(HonchoOperation.CREATE_PEER, ctx, createPeerRequest, null, null);
    }

    public Object getPeerCard(HonchoContext ctx, String peerId) throws HonchoCallException {
        return call(HonchoOperation.GET_PEER_CARD, ctx, null, pathVar("peerId", peerId), null);
    }

    public Object updatePeerCard(HonchoContext ctx, String peerId, Object cardData) throws HonchoCallException {
        return call(HonchoOperation.UPDATE_PEER_CARD, ctx, cardData, pathVar("peerId", peerId), null);
    }

    public Object getPeerRepresentation(HonchoContext ctx, String peerId) throws HonchoCallException {
        return call(HonchoOperation.GET_REPRESENTATION, ctx, null, pathVar("peerId", peerId), null);
    }

    public Object peerChat(HonchoContext ctx, String peerId, Object chatRequest) throws HonchoCallException {
        return call(HonchoOperation.PEER_CHAT, ctx, chatRequest, pathVar("peerId", peerId), null);
    }

    public Object searchPeers(HonchoContext ctx, String peerId, Object searchRequest) throws HonchoCallException {
        return call(HonchoOperation.SEARCH_PEERS, ctx, searchRequest, pathVar("peerId", peerId), null);
    }

    public Object listPeerConclusions(HonchoContext ctx, String peerId, Map<String, ?> filters) throws HonchoCallException {
        return call(HonchoOperation.LIST_PEER_CONCLUSIONS, ctx, null, pathVar("peerId", peerId), filters);
    }

    public Object listPeerSessions(HonchoContext ctx, String peerId, Map<String, ?> filters) throws HonchoCallException {
        return call(HonchoOperation.LIST_PEER_SESSIONS, ctx, null, pathVar("peerId", peerId), filters);
    }

    public Object queryPeerConclusions(HonchoContext ctx, String peerId, Object queryRequest) throws HonchoCallException {
        return call(HonchoOperation.QUERY_PEER_CONCLUSIONS, ctx, queryRequest, pathVar("peerId", peerId), null);
    }

    public Object listSessions(HonchoContext ctx, Map<String, ?> filters) throws HonchoCallException {
        return call(HonchoOperation.LIST_SESSIONS, ctx, null, null, filters);
    }

    public Object createSession(HonchoContext ctx, Object createSessionRequest) throws HonchoCallException {
        return call(HonchoOperation.CREATE_SESSION, ctx, createSessionRequest, null, null);
    }

    public Object getSession(HonchoContext ctx, String sessionId) throws HonchoCallException {
        return call(HonchoOperation.GET_SESSION, ctx, null, pathVar("sessionId", sessionId), null);
    }

    public Object deleteSession(HonchoContext ctx, String sessionId) throws HonchoCallException {
        return call(HonchoOperation.DELETE_SESSION, ctx, null, pathVar("sessionId", sessionId), null);
    }

    public Object listSessionMessages(HonchoContext ctx, String sessionId, Map<String, ?> filters) throws HonchoCallException {
        return call(HonchoOperation.LIST_SESSION_MESSAGES, ctx, null, pathVar("sessionId", sessionId), filters);
    }

    public Object addMessage(HonchoContext ctx, String sessionId, Object messageRequest) throws HonchoCallException {
        return call(HonchoOperation.ADD_MESSAGE, ctx, messageRequest, pathVar("sessionId", sessionId), null);
    }

    public Object getSessionContext(HonchoContext ctx, String sessionId, Integer tokens, Boolean summary) throws HonchoCallException {
        return call(
            HonchoOperation.GET_SESSION_CONTEXT,
            ctx,
            null,
            pathVar("sessionId", sessionId),
            contextQueryParams(tokens, summary)
        );
    }

    public Object getSessionSummaries(HonchoContext ctx, String sessionId) throws HonchoCallException {
        return call(HonchoOperation.GET_SESSION_SUMMARIES, ctx, null, pathVar("sessionId", sessionId), null);
    }

    public Object getSessionPeers(HonchoContext ctx, String sessionId) throws HonchoCallException {
        return call(HonchoOperation.GET_SESSION_PEERS, ctx, null, pathVar("sessionId", sessionId), null);
    }

    public Object searchSessionMessages(HonchoContext ctx, String sessionId, Object searchRequest) throws HonchoCallException {
        return call(HonchoOperation.SEARCH_SESSION_MESSAGES, ctx, searchRequest, pathVar("sessionId", sessionId), null);
    }

    public Object getQueueStatus(HonchoContext ctx) throws HonchoCallException {
        return call(HonchoOperation.GET_QUEUE_STATUS, ctx, null, null, null);
    }

    public Object searchMessages(HonchoContext ctx, Object searchRequest) throws HonchoCallException {
        return call(HonchoOperation.SEARCH_MESSAGES, ctx, searchRequest, null, null);
    }

    public Object scheduleDream(HonchoContext ctx, String peerId, Object dreamRequest) throws HonchoCallException {
        return call(HonchoOperation.SCHEDULE_DREAM, ctx, dreamRequest, pathVar("peerId", peerId), null);
    }

    public Object getWorkspaceInfo(HonchoContext ctx) throws HonchoCallException {
        return call(HonchoOperation.GET_WORKSPACE_INFO, ctx, null, null, null);
    }

    // ------------------------------------------------------------------
    // @Deprecated path-based methods — kept so the current
    // HonchoController continues to compile until T16 refactors it.
    // ------------------------------------------------------------------

    /**
     * Transitional — T16 will refactor the controller to use the
     * operation-based methods. Remove in a follow-up.
     */
    @Deprecated(forRemoval = true)
    public Object get(HonchoContext ctx, String path, Map<String, ?> query) {
        throw pathBasedRemoved();
    }

    /**
     * Transitional — T16 will refactor the controller to use the
     * operation-based methods. Remove in a follow-up.
     */
    @Deprecated(forRemoval = true)
    public Object post(HonchoContext ctx, String path, Map<String, ?> query, Object body) {
        throw pathBasedRemoved();
    }

    /**
     * Transitional — T16 will refactor the controller to use the
     * operation-based methods. Remove in a follow-up.
     */
    @Deprecated(forRemoval = true)
    public Object put(HonchoContext ctx, String path, Map<String, ?> query, Object body) {
        throw pathBasedRemoved();
    }

    /**
     * Transitional — T16 will refactor the controller to use the
     * operation-based methods. Remove in a follow-up.
     */
    @Deprecated(forRemoval = true)
    public Object delete(HonchoContext ctx, String path) {
        throw pathBasedRemoved();
    }

    private static UnsupportedOperationException pathBasedRemoved() {
        return new UnsupportedOperationException(
            "Path-based get/post/put/delete on HonchoProxyService are removed in T15; "
                + "use the operation-based call(HonchoOperation, HonchoContext, ...) entry point "
                + "or one of the 24 typed convenience methods. HonchoController migration is T16."
        );
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private static Map<String, String> pathVar(String key, String value) {
        return Map.of(key, value);
    }

    /**
     * Build the query-parameter map for {@code getSessionContext}, where
     * the two optional parameters ({@code tokens}, {@code summary}) are
     * passed as a flat map. {@code null} values are filtered out so the
     * resulting query string omits the key entirely — mirrors the
     * provider-side logic in {@code HonchoV3Client.contextQueryParams}.
     */
    private static Map<String, ?> contextQueryParams(Integer tokens, Boolean summary) {
        Map<String, Object> q = new LinkedHashMap<>();
        if (tokens != null) {
            q.put("tokens", tokens);
        }
        if (summary != null) {
            q.put("summary", summary);
        }
        return q;
    }
}
