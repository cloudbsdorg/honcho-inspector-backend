package com.revytechinc.honchoinspector.honcho;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Unwraps the single-key envelopes that Honcho v3 wraps around
 * "primitive" responses (text + list-of-strings endpoints).
 *
 * <p>Honcho v3 returns the same five response-shape categories the
 * UI audit identified:
 *
 * <ol>
 *   <li><b>Single-string envelope</b> &mdash; {@code { content | representation | peer_card }}
 *       on chat, representation, peer card. Without unwrapping, the
 *       frontend renders these as {@code [object Object]}.</li>
 *   <li><b>Pagination envelope</b> &mdash; {@code Page[T]} = {@code { items, total, page, size, pages }}
 *       on every {@code /list} endpoint. Unwrapped to {@code items[]}.</li>
 *   <li><b>Raw object</b> &mdash; returned directly (QueueStatus, Peer, etc.).</li>
 *   <li><b>Raw array</b> &mdash; returned directly (search results, conclusions list).</li>
 *   <li><b>Empty (204 No Content)</b> &mdash; body is {@code null}, treated as void.</li>
 * </ol>
 *
 * <p>This component is the single source of truth for envelope
 * unwrapping on the proxy. The frontend's
 * {@code api-response-shapes.ts} registry is kept in sync as a
 * defense-in-depth, but the canonical unwrap happens here so the UI
 * sees the inner value (a string, an array, or an object) directly.
 *
 * <h2>Why not a generic "if single-key, unwrap" heuristic?</h2>
 * Honcho v3 also returns raw objects with multiple keys (e.g.
 * {@code QueueStatus} has 4+ fields). A heuristic that
 * unwraps "if exactly one key" will mis-unwrap a raw object
 * whose only field happens to be a one-character string. The
 * per-operation table below is explicit: an endpoint either
 * returns an envelope with a known key, or it doesn't. There is
 * no middle ground.
 *
 * <h2>Why unwrap on the backend, not the frontend?</h2>
 * The proxy's job is to present a clean shape to the UI. The
 * "envelope or not?" decision is a property of the Honcho
 * deployment (one or two fields per endpoint), not the operator's
 * workflow. Doing the unwrap on the backend means every frontend
 * call site sees the same shape regardless of which Honcho version
 * the operator is running. The frontend can then focus on
 * presentation (markdown, mermaid, pop-out modals) without
 * re-implementing the envelope logic for every endpoint.
 */
@Component
public class HonchoResponseUnwrapper {

    /**
     * One row per Honcho v3 endpoint that wraps its result in a
     * single-key envelope. The key is the envelope field name.
     *
     * <p>Add a row here when a new envelope endpoint is added; the
     * tests in {@code HonchoResponseUnwrapperTest} will catch a
     * missing row (by exercising every operation).
     */
    private static final Map<HonchoOperation, String> ENVELOPE_FIELDS = Map.of(
        // { content: string | null } -> string
        HonchoOperation.PEER_CHAT, "content",
        // { representation: string } -> string
        HonchoOperation.GET_REPRESENTATION, "representation",
        // { peer_card: string[] | null } -> string[]
        HonchoOperation.GET_PEER_CARD, "peer_card",
        HonchoOperation.UPDATE_PEER_CARD, "peer_card"
    );

    /**
     * Endpoints whose response is a Honcho v3 {@code Page[T]}
     * pagination envelope ({@code { items, total, page, size, pages }}).
     * Unwrap to {@code items[]} so the frontend iterates a plain
     * array (matching the shape every other "list" call already uses).
     */
    private static final List<HonchoOperation> PAGINATION_OPS = List.of(
        HonchoOperation.LIST_PEERS,
        HonchoOperation.LIST_SESSIONS,
        HonchoOperation.LIST_PEER_SESSIONS,
        HonchoOperation.LIST_SESSION_MESSAGES,
        HonchoOperation.LIST_PEER_CONCLUSIONS
        // NOTE: Honcho v3's conclusions/list is exposed at the workspace
        // scope, not peer scope; the peer-scoped conclusion list is
        // LIST_PEER_CONCLUSIONS and returns Page[Conclusion].
    );

    /**
     * Endpoints whose response is a 204 No Content. The body is
     * {@code null} and the operation is effectively a side-effect
     * call; the frontend treats it as {@code Promise<void>}.
     */
    private static final List<HonchoOperation> EMPTY_OPS = List.of(
        HonchoOperation.SCHEDULE_DREAM
    );

    /**
     * Apply the per-operation unwrap. Returns the inner value (string,
     * array, or object) for envelope-shaped ops, the original body
     * for raw ops, or {@code null} for empty ops.
     *
     * <p>Called from {@link HonchoProxyService#call} before the result
     * is returned to the controller, so the controller and the
     * frontend see the unwrapped shape.
     *
     * @param op the {@link HonchoOperation} that was called (used as
     *     the unwrap key; an unknown op is passed through unchanged)
     * @param body the raw Honcho response body ({@code null} for 204)
     * @return the unwrapped body, or {@code body} if no unwrap applies
     */
    public Object unwrap(HonchoOperation op, Object body) {
        if (body == null) return null;
        // 1. Single-key envelope.
        String field = ENVELOPE_FIELDS.get(op);
        if (field != null) {
            return extractField(body, field, op);
        }
        // 2. Pagination envelope.
        if (PAGINATION_OPS.contains(op)) {
            return extractPage(body, op);
        }
        // 3. Empty (204). Should be unreachable for non-null body,
        // but defensive: return as-is.
        if (EMPTY_OPS.contains(op)) {
            return body;
        }
        // 4. Raw object or raw array: pass through.
        return body;
    }

    /**
     * Extract the named field from a single-key envelope. If the
     * body is a {@code Map} and contains the field, return it;
     * otherwise return the body unchanged (defensive: an
     * envelope-less response still flows through). This matches the
     * frontend's tolerance for null or empty envelopes.
     */
    private Object extractField(Object body, String field, HonchoOperation op) {
        if (body instanceof Map<?, ?> map) {
            Object value = ((Map<String, Object>) map).get(field);
            return value; // may be null (valid for "no card yet" etc.)
        }
        // Body is not a single-key envelope. Either Honcho changed
        // the contract or we got a different shape. Log via the
        // exception so the operator sees the failure in the JSON
        // response, not just in logs.
        throw new HonchoCallException(
            "Expected " + op + " response to be a single-key envelope with field '"
                + field + "', but got: " + summarize(body),
            502, null
        );
    }

    /**
     * Extract the {@code items[]} from a pagination envelope. If
     * {@code items} is missing, log and return an empty list
     * (matches the existing frontend fallback).
     */
    private Object extractPage(Object body, HonchoOperation op) {
        if (body instanceof Map<?, ?> map) {
            Object items = ((Map<String, Object>) map).get("items");
            if (items == null) return List.of();
            if (items instanceof List<?>) return items;
        }
        throw new HonchoCallException(
            "Expected " + op + " response to be a pagination envelope with 'items' array, but got: "
                + summarize(body),
            502, null
        );
    }

    private static String summarize(Object body) {
        if (body == null) return "null";
        String s = body.toString();
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
