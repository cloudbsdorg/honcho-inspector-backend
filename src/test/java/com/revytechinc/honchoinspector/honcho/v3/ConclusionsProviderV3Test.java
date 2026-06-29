package com.revytechinc.honchoinspector.honcho.v3;

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
 * Contract test for {@link ConclusionsProviderV3}. Three tests covering the
 * v3 contract for {@link HonchoOperation#LIST_PEER_CONCLUSIONS}:
 * <ol>
 *   <li>{@code operations()} advertises the op; {@code supportedVersions()}
 *       returns exactly {@code {V3}}.</li>
 *   <li>{@code pathTemplate(op)} + {@code httpMethod(op)} match Honcho v3:
 *       POST {@code /v3/workspaces/{ws}/conclusions/list}.</li>
 *   <li>URL construction produces the right absolute URL and
 *       {@link ConclusionsProviderV3#buildFiltersBody} wraps a flat filter
 *       map in Honcho's {@code {filters: {...}}} envelope.</li>
 * </ol>
 */
class ConclusionsProviderV3Test {

    private static final HonchoContext CTX = new HonchoContext(
        "test-api-key", "https://api.honcho.dev", "ws-42", "test-user"
    );

    @Test
    void listPeerConclusions_advertisesV3PostOnConclusionsListEndpoint() {
        ConclusionsProviderV3 provider = newProvider();

        assertThat(provider.operations())
            .as("LIST_PEER_CONCLUSIONS moved out of PeerQueryProviderV3 into ConclusionsProviderV3")
            .contains(HonchoOperation.LIST_PEER_CONCLUSIONS);
        assertThat(provider.supportedVersions())
            .as("ConclusionsProviderV3 is v3-only — no other version should be claimed")
            .containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.LIST_PEER_CONCLUSIONS))
            .isEqualTo("workspaces/{ws}/conclusions/list");
        assertThat(provider.httpMethod(HonchoOperation.LIST_PEER_CONCLUSIONS))
            .as("Honcho v3 changed the verb from GET (per-peer) to POST (workspace-level)")
            .isEqualTo(HttpMethod.POST);
        assertThat(urlFor())
            .as("URL omits {peerId} because the upstream endpoint is workspace-level")
            .isEqualTo("https://api.honcho.dev/v3/workspaces/ws-42/conclusions/list");
    }

    @Test
    void buildFiltersBody_wrapsFlatFiltersInEnvelope_andFiltersToWhitelist() {
        // The whitelist excludes fields that Honcho v3 doesn't know
        // about (e.g. "size" / "limit" are PAGE envelope fields, not
        // filter columns — forwarding them triggers 422 "Column size
        // does not exist on Document"). observed_id is filled in from
        // the peerId path variable so callers can target a specific
        // peer without spelling it out in the body.
        Map<String, Object> body = ConclusionsProviderV3.buildFiltersBody(
            Map.of("observed_id", "alice", "size", 10, "garbage_key", "x"),
            null,
            Map.of("peerId", "p-99")
        );
        assertThat(body).containsOnlyKeys("filters");
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) body.get("filters");
        assertThat(filters)
            .as("Only whitelisted filter keys survive; peerId fills observed_id when absent")
            .containsEntry("observed_id", "alice")
            .doesNotContainKey("size")
            .doesNotContainKey("garbage_key");
        assertThat(filters.get("observed_id")).isEqualTo("alice");
    }

    @Test
    void buildFiltersBody_passesPreWrappedBodyThroughVerbatimButStillFiltersUnwhitelistedKeys() {
        // PreWrapped body: the wrapper's inner filters map is passed
        // through verbatim (caller might be using a custom Honcho key
        // the proxy doesn't know about), but observed_id is backfilled
        // from pathVars IF absent — the controller's contract says
        // "POST {} and get a peer's full list", and that path needs
        // observed_id to be set somewhere.
        Map<String, Object> preWrapped = Map.of(
            "filters", Map.of("observer_id", "p-1", "size", 5, "garbage_key", "x")
        );
        Map<String, Object> body = ConclusionsProviderV3.buildFiltersBody(
            preWrapped, null, Map.of("peerId", "p-99")
        );
        // Inner filters map is preserved + observed_id is backfilled.
        // The OUTER map is a different object (we had to rebuild it
        // to update the inner).
        assertThat(body).isNotSameAs(preWrapped);
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) body.get("filters");
        assertThat(filters)
            .containsEntry("observer_id", "p-1")
            .containsEntry("size", 5)
            .containsEntry("garbage_key", "x")
            .containsEntry("observed_id", "p-99");
    }

    @Test
    void buildFiltersBody_preWrappedDoesNotBackfillWhenObservedIdPresent() {
        // If the caller already specified observed_id, the proxy
        // should NOT backfill — the caller is the source of truth.
        // The preWrapped body is preserved verbatim (size flows
        // through here because it's a preWrapped case; only the FLAT
        // body path applies the whitelist).
        Map<String, Object> preWrapped = Map.of(
            "filters", Map.of("observed_id", "alice", "size", 5)
        );
        Map<String, Object> body = ConclusionsProviderV3.buildFiltersBody(
            preWrapped, null, Map.of("peerId", "p-99")
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) body.get("filters");
        assertThat(filters).containsEntry("observed_id", "alice");
        // observed_id is alice (caller's value), NOT p-99 (pathVar).
        assertThat(filters.get("observed_id")).isEqualTo("alice");
        // The whole body is unchanged (no backfill needed).
        assertThat(body).isEqualTo(preWrapped);
    }

    @Test
    void buildFiltersBody_handlesAbsentFiltersGracefully() {
        Map<String, Object> body = ConclusionsProviderV3.buildFiltersBody(
            null, null, null
        );
        assertThat(body).containsOnlyKeys("filters");
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) body.get("filters");
        assertThat(filters).isEmpty();
    }

    @Test
    void buildFiltersBody_includesObservedIdFromPathVarWhenAbsent() {
        // pathVars is the canonical way to target a specific peer; if
        // the caller doesn't spell out observed_id we backfill it from
        // the path variable. This matches the controller's documented
        // "POST {} and get the peer's full list" path.
        Map<String, Object> body = ConclusionsProviderV3.buildFiltersBody(
            Map.of(),
            null,
            Map.of("peerId", "p-42")
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) body.get("filters");
        assertThat(filters).containsEntry("observed_id", "p-42");
    }

    private static ConclusionsProviderV3 newProvider() {
        RestClient mockClient = mock(RestClient.class);
        return new ConclusionsProviderV3(mockClient);
    }

    private static String urlFor() {
        ConclusionsProviderV3 provider = newProvider();
        String template = provider.pathTemplate(HonchoOperation.LIST_PEER_CONCLUSIONS);
        String substituted = V3ProviderSupport.substitutePath(template, CTX, null);
        return V3ProviderSupport.buildUrl(CTX.baseUrl(), CTX.apiVersion().pathPrefix(), substituted, Map.of());
    }
}
