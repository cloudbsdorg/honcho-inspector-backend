package com.revytechinc.honchoinspector.honcho;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Unit coverage for the per-operation unwrap table in
 * {@link HonchoResponseUnwrapper}. This is the one place we encode
 * "which Honcho v3 response shapes are envelopes and which are not"
 * — a missing row means a regression in the proxy that the
 * integration tests catch only when the user notices a UI bug.
 *
 * <p>The unwrapper has three cases:
 * <ol>
 *   <li>Single-key envelope (e.g. {@code {peer_card: null}} →
 *       unwrap to the inner value, which is null).</li>
 *   <li>Pagination envelope ({@code {items, total, page, size,
 *       pages}}) → pass through unchanged; the frontend consumes
 *       {@code page.items.map}.</li>
 *   <li>Raw object / array → pass through unchanged.</li>
 * </ol>
 *
 * <p>The "Honcho returned an empty object" case is the one that
 * bit us in production: an empty {@code {}} means the
 * peer-resource had no card facts yet. The unwrapper should
 * return {@code null} for missing fields, and the envelope advice
 * then wraps that as {@code {data:null, error:null, meta:null}}.
 * A test that documents this contract is critical so a future
 * change to the unwrapper doesn't reintroduce the "empty object
 * on the wire" bug.
 */
class HonchoResponseUnwrapperTest {

    private final HonchoResponseUnwrapper unwrapper = new HonchoResponseUnwrapper();

    @Nested
    @DisplayName("single-key envelope ops")
    class SingleKeyEnvelopes {

        @Test
        void unwrapsPeerCardArray() {
            Object body = Map.of("peer_card", List.of("fact-1", "fact-2"));
            assertThat(unwrapper.unwrap(HonchoOperation.GET_PEER_CARD, body))
                .isEqualTo(List.of("fact-1", "fact-2"));
        }

        @Test
        void unwrapsPeerCardNull() {
            // Honcho returns {"peer_card": null} when the peer has
            // no card facts yet. The unwrapper extracts the field
            // (which is null) so the controller body is null and
            // the envelope advice produces {data:null, ...} on the
            // wire — distinguishable from "Honcho returned {}"
            // which would mean a different transport-level state.
            // (Map.of rejects null values, so we use HashMap.)
            Map<String, Object> body = new HashMap<>();
            body.put("peer_card", null);
            assertThat(unwrapper.unwrap(HonchoOperation.GET_PEER_CARD, body)).isNull();
        }

        @Test
        void unwrapsRepresentationString() {
            Object body = Map.of("representation", "Honcho knows X.");
            assertThat(unwrapper.unwrap(HonchoOperation.GET_REPRESENTATION, body))
                .isEqualTo("Honcho knows X.");
        }

        @Test
        void unwrapsChatContentString() {
            Object body = Map.of("content", "Hi from Honcho.");
            assertThat(unwrapper.unwrap(HonchoOperation.PEER_CHAT, body))
                .isEqualTo("Hi from Honcho.");
        }

        @Test
        void unwrapsUpdatePeerCardField() {
            // POST /v3/.../peers/{id}/card also returns {peer_card: ...}
            Object body = Map.of("peer_card", List.of("a", "b", "c"));
            assertThat(unwrapper.unwrap(HonchoOperation.UPDATE_PEER_CARD, body))
                .isEqualTo(List.of("a", "b", "c"));
        }

        @Test
        void emptyBodyFromHonchoYieldsNull() {
            // The edge case that broke the live UI: Honcho returned
            // {} (no peer_card key) for a peer's GET /card. The
            // unwrapper must return null, NOT throw a 502. The
            // advice then wraps null as {data:null}.
            Object body = Map.of();
            assertThat(unwrapper.unwrap(HonchoOperation.GET_PEER_CARD, body)).isNull();
        }

        @Test
        void nullBodyPassesThrough() {
            // 204 No Content from Honcho: body is null, op is in
            // EMPTY_OPS. The unwrapper returns null.
            assertThat(unwrapper.unwrap(HonchoOperation.SCHEDULE_DREAM, null)).isNull();
        }

        @Test
        void singleKeyButWrongShapeThrows502() {
            // Honcho changed the contract and we get a raw object
            // instead of an envelope — fail loud at the proxy
            // boundary so the operator sees the breakage, not just
            // a silent [object Object] in the UI.
            Object body = List.of("not a map");
            assertThatThrownBy(() -> unwrapper.unwrap(HonchoOperation.GET_PEER_CARD, body))
                .isInstanceOf(HonchoCallException.class)
                .satisfies(e -> {
                    HonchoCallException hce = (HonchoCallException) e;
                    assertThat(hce.status()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
                });
        }
    }

    @Nested
    @DisplayName("pagination envelope ops")
    class PaginationEnvelopes {

        @Test
        void passesThroughUnchanged() {
            // The frontend's `refreshPeersInternal` and friends do
            // `page.items.map(...)` directly. The unwrapper leaves
            // the {items, total, page, size, pages} envelope intact
            // so that pattern keeps working without a frontend
            // change.
            Object body = Map.of(
                "items", List.of("s-1", "s-2"),
                "total", 2, "page", 1, "size", 50, "pages", 1
            );
            Object result = unwrapper.unwrap(HonchoOperation.LIST_PEERS, body);
            assertThat(result).isSameAs(body);
        }

        @Test
        void passesThroughUnchangedForListSessions() {
            Object body = Map.of("items", List.of(), "total", 0, "page", 1, "size", 50, "pages", 0);
            assertThat(unwrapper.unwrap(HonchoOperation.LIST_SESSIONS, body)).isSameAs(body);
        }
    }

    @Nested
    @DisplayName("raw object / array ops")
    class RawOps {

        @Test
        void queueStatusRawObjectPassesThrough() {
            Object body = Map.of(
                "total_work_units", 4,
                "completed_work_units", 4,
                "in_progress_work_units", 0,
                "pending_work_units", 0
            );
            assertThat(unwrapper.unwrap(HonchoOperation.GET_QUEUE_STATUS, body)).isSameAs(body);
        }

        @Test
        void searchMessagesRawArrayPassesThrough() {
            // POST /v3/.../search returns a raw array (not an
            // envelope). The unwrapper must NOT mis-unwrap it as a
            // single-key envelope just because the array is "one
            // thing" in JSON.
            Object body = List.of("msg-1", "msg-2");
            assertThat(unwrapper.unwrap(HonchoOperation.SEARCH_MESSAGES, body)).isSameAs(body);
        }
    }
}
