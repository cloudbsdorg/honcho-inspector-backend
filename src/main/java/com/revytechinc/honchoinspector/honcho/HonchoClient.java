package com.revytechinc.honchoinspector.honcho;

import com.revytechinc.honchoinspector.model.HonchoContext;

import java.util.Map;
import java.util.Set;

/**
 * Single Honcho-agnostic client surface that the controller layer targets.
 *
 * <p>Each method on this interface corresponds to exactly one operation
 * defined by {@link HonchoOperation}, and each such operation in turn maps
 * to one endpoint on the legacy {@code HonchoController}. The pairing
 * ensures that the new dispatch pipeline covers every current Honcho-backed
 * route without dropping or duplicating any of them.
 *
 * <p>Return types are intentionally {@link Object} so the client surface is
 * not coupled to Honcho SDK classes. Concrete implementations may deserialize
 * JSON into SDK types if they wish, but the controller layer above this
 * interface does not depend on those types — it forwards the body through to
 * the browser.
 *
 * <p>This interface is a pure contract for now: it declares the method
 * signatures, the {@link HonchoCallException} that callers must handle, and
 * the set of {@link HonchoApiVersion}s the implementation supports. Concrete
 * dispatch logic — selecting the right HTTP verb, path, and headers — lives
 * in the per-version clients (e.g. {@code HonchoV3Client}) introduced in later
 * tasks. No default methods exist on this interface on purpose.
 *
 * <p>Implementations are registered with the framework by Spring and looked
 * up via {@link #supportedVersions()} so the dispatch table can be built at
 * boot without reflection on class names.
 */
public interface HonchoClient {

    /**
     * List peers in the workspace addressed by {@code ctx.workspaceId()}.
     * Corresponds to {@link HonchoOperation#LIST_PEERS}.
     *
     * @param ctx     authenticated Honcho call context (apiKey, baseUrl, workspaceId, userName)
     * @param filters optional query filters (Honcho list endpoint parameters)
     */
    Object listPeers(HonchoContext ctx, Map<String, ?> filters) throws HonchoCallException;

    /**
     * Create a new peer in the workspace addressed by {@code ctx.workspaceId()}.
     * Corresponds to {@link HonchoOperation#CREATE_PEER}.
     */
    Object createPeer(HonchoContext ctx, Object createPeerRequest) throws HonchoCallException;

    /**
     * Update an existing peer's mutable fields (e.g. metadata).
     * Corresponds to {@link HonchoOperation#UPDATE_PEER}.
     */
    Object updatePeer(HonchoContext ctx, String peerId, Object updatePeerRequest) throws HonchoCallException;

    /**
     * Batch-create one or more conclusions in the workspace.
     * Corresponds to {@link HonchoOperation#CREATE_CONCLUSIONS}.
     *
     * <p>The {@code requestBody} is Honcho's {@code ConclusionBatchCreate}
     * envelope ({@code {conclusions: [...]}}); implementations forward
     * it verbatim to the workspace-level
     * {@code POST /conclusions} endpoint.
     */
    Object createConclusions(HonchoContext ctx, Object conclusionsBatch) throws HonchoCallException;

    /**
     * Delete a single conclusion by id.
     * Corresponds to {@link HonchoOperation#DELETE_CONCLUSION}.
     */
    Object deleteConclusion(HonchoContext ctx, String conclusionId) throws HonchoCallException;

    /**
     * Fetch the peer card (biographical facts) for {@code peerId}.
     * Corresponds to {@link HonchoOperation#GET_PEER_CARD}.
     */
    Object getPeerCard(HonchoContext ctx, String peerId) throws HonchoCallException;

    /**
     * Replace the peer card for {@code peerId} with {@code cardData}.
     * Corresponds to {@link HonchoOperation#UPDATE_PEER_CARD}.
     */
    Object updatePeerCard(HonchoContext ctx, String peerId, Object cardData) throws HonchoCallException;

    /**
     * Fetch the textual representation for {@code peerId}.
     * Corresponds to {@link HonchoOperation#GET_REPRESENTATION}.
     *
     * @param body optional request body; Honcho v3 accepts an empty
     *             {@code {}} when no options are needed
     */
    Object getPeerRepresentation(HonchoContext ctx, String peerId, Object body) throws HonchoCallException;

    /**
     * Issue a natural-language chat query against {@code peerId}.
     * Corresponds to {@link HonchoOperation#PEER_CHAT}.
     */
    Object peerChat(HonchoContext ctx, String peerId, Object chatRequest) throws HonchoCallException;

    /**
     * Semantic search over a peer's documents.
     * Corresponds to {@link HonchoOperation#SEARCH_PEERS}.
     */
    Object searchPeers(HonchoContext ctx, String peerId, Object searchRequest) throws HonchoCallException;

    /**
     * List conclusions (derived facts) about {@code peerId}.
     * Corresponds to {@link HonchoOperation#LIST_PEER_CONCLUSIONS}.
     *
     * @param body Honcho v3 {@code ConclusionGet} envelope: {@code {filters: {...}}}.
     *             Implementations forward it to the workspace-level
     *             {@code POST /conclusions/list} endpoint verbatim.
     */
    Object listPeerConclusions(HonchoContext ctx, String peerId, Object body) throws HonchoCallException;

    /**
     * List sessions the peer {@code peerId} participates in.
     * Corresponds to {@link HonchoOperation#LIST_PEER_SESSIONS}.
     */
    Object listPeerSessions(HonchoContext ctx, String peerId, Map<String, ?> filters) throws HonchoCallException;

    /**
     * Semantic search over a peer's conclusions.
     * Corresponds to {@link HonchoOperation#QUERY_PEER_CONCLUSIONS}.
     */
    Object queryPeerConclusions(HonchoContext ctx, String peerId, Object queryRequest) throws HonchoCallException;

    /**
     * List all sessions in the workspace.
     * Corresponds to {@link HonchoOperation#LIST_SESSIONS}.
     */
    Object listSessions(HonchoContext ctx, Map<String, ?> filters) throws HonchoCallException;

    /**
     * Create a new session in the workspace.
     * Corresponds to {@link HonchoOperation#CREATE_SESSION}.
     */
    Object createSession(HonchoContext ctx, Object createSessionRequest) throws HonchoCallException;

    /**
     * Fetch a session by id.
     * Corresponds to {@link HonchoOperation#GET_SESSION}.
     */
    Object getSession(HonchoContext ctx, String sessionId) throws HonchoCallException;

    /**
     * Delete a session by id.
     * Corresponds to {@link HonchoOperation#DELETE_SESSION}.
     */
    Object deleteSession(HonchoContext ctx, String sessionId) throws HonchoCallException;

    /**
     * Update a session's mutable fields (e.g. metadata, configuration).
     * Corresponds to {@link HonchoOperation#UPDATE_SESSION}.
     */
    Object updateSession(HonchoContext ctx, String sessionId, Object updateSessionRequest) throws HonchoCallException;

    /**
     * List messages in a session, with optional filters.
     * Corresponds to {@link HonchoOperation#LIST_SESSION_MESSAGES}.
     */
    Object listSessionMessages(HonchoContext ctx, String sessionId, Map<String, ?> filters) throws HonchoCallException;

    /**
     * Append messages to a session.
     * Corresponds to {@link HonchoOperation#ADD_MESSAGE}.
     */
    Object addMessage(HonchoContext ctx, String sessionId, Object messageRequest) throws HonchoCallException;

    /**
     * Update a single message in a session.
     * Corresponds to {@link HonchoOperation#UPDATE_MESSAGE}.
     */
    Object updateMessage(HonchoContext ctx, String sessionId, String messageId, Object updateMessageRequest) throws HonchoCallException;

    /**
     * Build a context window for the session, suitable for LLM prompts.
     * Corresponds to {@link HonchoOperation#GET_SESSION_CONTEXT}.
     *
     * @param tokens  optional target token budget; {@code null} to use Honcho's default
     * @param summary whether to include a summary of older messages
     */
    Object getSessionContext(HonchoContext ctx, String sessionId, Integer tokens, Boolean summary) throws HonchoCallException;

    /**
     * Fetch rolling summaries for a session.
     * Corresponds to {@link HonchoOperation#GET_SESSION_SUMMARIES}.
     */
    Object getSessionSummaries(HonchoContext ctx, String sessionId) throws HonchoCallException;

    /**
     * List peers participating in a session.
     * Corresponds to {@link HonchoOperation#GET_SESSION_PEERS}.
     */
    Object getSessionPeers(HonchoContext ctx, String sessionId) throws HonchoCallException;

    /**
     * Semantic search over a session's messages.
     * Corresponds to {@link HonchoOperation#SEARCH_SESSION_MESSAGES}.
     */
    Object searchSessionMessages(HonchoContext ctx, String sessionId, Object searchRequest) throws HonchoCallException;

    /**
     * Background work-unit queue status for the workspace.
     * Corresponds to {@link HonchoOperation#GET_QUEUE_STATUS}.
     */
    Object getQueueStatus(HonchoContext ctx) throws HonchoCallException;

    /**
     * Workspace-wide semantic search over messages.
     * Corresponds to {@link HonchoOperation#SEARCH_MESSAGES}.
     */
    Object searchMessages(HonchoContext ctx, Object searchRequest) throws HonchoCallException;

    /**
     * Schedule a dream (memory-consolidation) for {@code peerId}.
     * Corresponds to {@link HonchoOperation#SCHEDULE_DREAM}.
     */
    Object scheduleDream(HonchoContext ctx, String peerId, Object dreamRequest) throws HonchoCallException;

    /**
     * Fetch workspace metadata (id, name, etc.).
     * Corresponds to {@link HonchoOperation#GET_WORKSPACE_INFO}.
     */
    Object getWorkspaceInfo(HonchoContext ctx) throws HonchoCallException;

    /**
     * Sum the total message count across every session in the workspace
     * addressed by {@code ctx}. Implementation strategy: list sessions
     * ({@code POST /v3/workspaces/{ws}/sessions/list} with {@code size=100}),
     * iterate their ids, and for each one issue
     * {@code POST /v3/workspaces/{ws}/sessions/{sid}/messages/list} with
     * {@code size=1} (small wire payload) so the only field the caller
     * reads is the {@code Page[Message]}'s {@code total}.
     *
     * <p>This is a derived aggregate, not a single
     * {@link HonchoOperation}. It does NOT map to any one upstream
     * endpoint — it composes {@link #listSessions(HonchoContext, Map)}
     * with {@link #listSessionMessages(HonchoContext, String, Map)} —
     * so it intentionally does not live on the
     * {@link HonchoOperation} enum.
     *
     * <p>The returned value reflects the LIVE Honcho state — messages
     * added by any path count (this backend, prior backend instances,
     * direct Honcho API calls, other clients). The dashboard uses this
     * for its "Messages in workspace" KPI card, replacing the older
     * proxy-only counter which would show {@code 0} whenever the user
     * added messages through any other path. See {@link HonchoMetrics}
     * for the per-profile 60s cache that fronts this call.
     *
     * <p>Honcho caps {@code size} at 100; a workspace with more than
     * 100 sessions will under-count this call. Acceptable for v1 — the
     * operator dashboard is human-readable, not a billing source of
     * truth. A future patch can iterate {@code page=1..N}.
     *
     * @throws HonchoCallException if any underlying Honcho call fails;
     *         callers (e.g. {@link HonchoMetrics}) are expected to
     *         translate this into a non-5xx dashboard response.
     */
    double totalWorkspaceMessages(HonchoContext ctx) throws HonchoCallException;

    /**
     * Generic dispatch entry point used by {@code HonchoProxyService} (T15)
     * to forward any {@link HonchoOperation} without knowing which typed
     * convenience method maps to it.
     *
     * <p>Implementations are expected to resolve {@code op} to the right
     * provider (via their per-instance {@code HonchoProviderRegistry}) and
     * invoke it with {@code (ctx, requestBody, pathVars, queryParams)} —
     * exactly what the typed convenience methods on this interface do.
     * The 24 typed methods on the concrete {@code HonchoV3Client}
     * implementation are thin one-liners that call this method with the
     * correct {@link HonchoOperation} and {@code pathVars} built from
     * the typed arguments, so the dispatch path is uniform regardless
     * of whether a caller uses a typed method or {@code call(...)}.
     *
     * <p>This method is declared {@code abstract} on purpose — there is no
     * useful default, and silently routing to a "first method wins"
     * fallback would mask configuration errors.
     *
     * @param op          the operation to execute (must be in the implementation's
     *                    {@link HonchoProviderRegistry} coverage set)
     * @param ctx         authenticated Honcho call context
     * @param requestBody deserialized JSON body, or {@code null} for GET / DELETE
     * @param pathVars    path placeholders keyed by template name
     *                    (e.g. {@code Map.of("peerId", "abc")}), or {@code null}
     * @param queryParams query-string parameters, or {@code null}
     * @return the deserialized response body, or {@code null} for 204
     */
    Object call(
        HonchoOperation op,
        HonchoContext ctx,
        Object requestBody,
        Map<String, String> pathVars,
        Map<String, ?> queryParams
    ) throws HonchoCallException;

    /**
     * The set of {@link HonchoApiVersion}s this implementation can serve.
     *
     * <p>Used by the client factory (introduced in a later task) to index
     * {@code HonchoClient} beans by supported version. A V3 implementation
     * returns {@code Set.of(V3)}; a future V4 implementation returns
     * {@code Set.of(V4)}; an implementation that supports both returns both.
     */
    Set<HonchoApiVersion> supportedVersions();
}
