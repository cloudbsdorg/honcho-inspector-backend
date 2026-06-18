package com.revytechinc.honchoinspector.honcho;

/**
 * Catalog of every Honcho-backed operation the inspector currently exposes.
 *
 * <p>Each constant maps to exactly one method on the legacy
 * {@code HonchoController} {@code /api/*} endpoint AND its corresponding
 * Honcho v3 upstream path. The pairing is documented in the constant's
 * Javadoc so the v2→v3 migration is visible at a glance.
 *
 * <p>URLs and HTTP methods are <strong>not</strong> stored here — those belong
 * to the v3 providers ({@code honcho.v3.*}) and are wired in T10–T13. This
 * enum is a stable identity token used by the provider dispatch table.
 *
 * <p>This enum is plain Java — no Spring annotations.
 */
public enum HonchoOperation {

    /** GET {@code /api/peers} → POST {@code /v3/workspaces/{ws}/peers/list}. */
    LIST_PEERS,

    /** POST {@code /api/peers} → POST {@code /v3/workspaces/{ws}/peers}. */
    CREATE_PEER,

    /** GET {@code /api/peers/{id}/card} → GET {@code /v3/workspaces/{ws}/peers/{id}/card}. */
    GET_PEER_CARD,

    /** POST {@code /api/peers/{id}/card} → POST {@code /v3/workspaces/{ws}/peers/{id}/card}. */
    UPDATE_PEER_CARD,

    /** GET {@code /api/peers/{id}/representation} → GET {@code /v3/workspaces/{ws}/peers/{id}/representation}. */
    GET_REPRESENTATION,

    /** POST {@code /api/peers/{id}/chat} → POST {@code /v3/workspaces/{ws}/peers/{id}/chat}. */
    PEER_CHAT,

    /** POST {@code /api/peers/{id}/search} → POST {@code /v3/workspaces/{ws}/peers/{id}/search}. */
    SEARCH_PEERS,

    /** GET {@code /api/peers/{id}/conclusions} → GET {@code /v3/workspaces/{ws}/peers/{id}/conclusions}. */
    LIST_PEER_CONCLUSIONS,

    /** GET {@code /api/peers/{id}/sessions} → GET {@code /v3/workspaces/{ws}/peers/{id}/sessions}. */
    LIST_PEER_SESSIONS,

    /** POST {@code /api/peers/{id}/conclusions/query} → POST {@code /v3/workspaces/{ws}/peers/{id}/conclusions/query}. */
    QUERY_PEER_CONCLUSIONS,

    /** GET {@code /api/sessions} → GET {@code /v3/workspaces/{ws}/sessions}. */
    LIST_SESSIONS,

    /** POST {@code /api/sessions} → POST {@code /v3/workspaces/{ws}/sessions}. */
    CREATE_SESSION,

    /** GET {@code /api/sessions/{id}} → GET {@code /v3/workspaces/{ws}/sessions/{id}}. */
    GET_SESSION,

    /** DELETE {@code /api/sessions/{id}} → DELETE {@code /v3/workspaces/{ws}/sessions/{id}}. */
    DELETE_SESSION,

    /** GET {@code /api/sessions/{id}/messages} → GET {@code /v3/workspaces/{ws}/sessions/{id}/messages}. */
    LIST_SESSION_MESSAGES,

    /** POST {@code /api/sessions/{id}/messages} → POST {@code /v3/workspaces/{ws}/sessions/{id}/messages}. */
    ADD_MESSAGE,

    /** GET {@code /api/sessions/{id}/context} → GET {@code /v3/workspaces/{ws}/sessions/{id}/context}. */
    GET_SESSION_CONTEXT,

    /** GET {@code /api/sessions/{id}/summaries} → GET {@code /v3/workspaces/{ws}/sessions/{id}/summaries}. */
    GET_SESSION_SUMMARIES,

    /** GET {@code /api/sessions/{id}/peers} → GET {@code /v3/workspaces/{ws}/sessions/{id}/peers}. */
    GET_SESSION_PEERS,

    /** POST {@code /api/sessions/{id}/search} → POST {@code /v3/workspaces/{ws}/sessions/{id}/search}. */
    SEARCH_SESSION_MESSAGES,

    /** GET {@code /api/queue-status} → GET {@code /v3/workspaces/{ws}/queue-status}. */
    GET_QUEUE_STATUS,

    /** POST {@code /api/search} → POST {@code /v3/workspaces/{ws}/search}. */
    SEARCH_MESSAGES,

    /** POST {@code /api/dream} → POST {@code /v3/workspaces/{ws}/peers/{peerId}/dreams}. */
    SCHEDULE_DREAM,

    /** GET {@code /api/workspace/info} → GET {@code /v3/workspaces/{ws}}. */
    GET_WORKSPACE_INFO
}
