package com.revytechinc.honchoinspector.honcho.v3;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.honcho.HonchoClient;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;
import com.revytechinc.honchoinspector.honcho.HonchoProvider;
import com.revytechinc.honchoinspector.model.HonchoContext;

/**
 * Honcho v3 implementation of the workspace-level conclusions cluster.
 *
 * <p>The conclusions resource moved up one level in v3: instead of being
 * nested under each peer ({@code /peers/{peerId}/conclusions}, which
 * returns 404 on this Honcho version), the upstream API exposes
 * {@code POST /v3/workspaces/{ws}/conclusions/list} with the peer filter
 * expressed as a body field ({@code filters.observed_id} /
 * {@code filters.observer_id}) per the
 * {@code ConclusionGet} schema in Honcho's OpenAPI spec.
 *
 * <p>This provider owns the three workspace-level conclusions operations:
 * <ul>
 *   <li>{@link HonchoOperation#LIST_PEER_CONCLUSIONS} &mdash;
 *       {@code POST /v3/workspaces/{ws}/conclusions/list}, the
 *       v3 list endpoint.</li>
 *   <li>{@link HonchoOperation#CREATE_CONCLUSIONS} &mdash;
 *       {@code POST /v3/workspaces/{ws}/conclusions}, the batch-create
 *       endpoint that accepts up to 100 conclusions per call.</li>
 *   <li>{@link HonchoOperation#DELETE_CONCLUSION} &mdash;
 *       {@code DELETE /v3/workspaces/{ws}/conclusions/{conclusionId}}.</li>
 * </ul>
 *
 * <p>Request body shape for list (per Honcho v3 OpenAPI):
 * <pre>
 * { "filters": { "observed_id": "&lt;peerId&gt;", "size": 50, ... } }
 * </pre>
 *
 * <p>Request body shape for create:
 * <pre>
 * { "conclusions": [ { "content": "...", "observer_id": "...", "observed_id": "...", "session_id": "..." }, ... ] }
 * </pre>
 *
 * <p>Response shape (list / create): {@code Page<Conclusion>} ({@code items,
 * total, page, size, pages}). The proxy forwards this Honcho envelope to
 * the browser unchanged. Delete is a 204 No Content.
 */
@Component
public class ConclusionsProviderV3 implements HonchoProvider {

    private static final Set<HonchoOperation> OPS = EnumSet.of(
        HonchoOperation.LIST_PEER_CONCLUSIONS,
        HonchoOperation.CREATE_CONCLUSIONS,
        HonchoOperation.DELETE_CONCLUSION
    );

    private final RestClient http;

    public ConclusionsProviderV3(RestClient honchoRestClient) {
        this.http = honchoRestClient;
    }

    @Override
    public Set<HonchoOperation> operations() {
        return OPS;
    }

    @Override
    public Set<HonchoApiVersion> supportedVersions() {
        return EnumSet.of(HonchoApiVersion.V3);
    }

    @Override
    public String pathTemplate(HonchoOperation op) {
        return switch (op) {
            case LIST_PEER_CONCLUSIONS -> "workspaces/{ws}/conclusions/list";
            case CREATE_CONCLUSIONS   -> "workspaces/{ws}/conclusions";
            case DELETE_CONCLUSION    -> "workspaces/{ws}/conclusions/{conclusionId}";
            default -> throw new UnsupportedOperationException(
                "ConclusionsProviderV3 has no path template for " + op);
        };
    }

    @Override
    public HttpMethod httpMethod(HonchoOperation op) {
        return switch (op) {
            case LIST_PEER_CONCLUSIONS, CREATE_CONCLUSIONS -> HttpMethod.POST;
            case DELETE_CONCLUSION                          -> HttpMethod.DELETE;
            default -> throw new UnsupportedOperationException(
                "ConclusionsProviderV3 has no HTTP method for " + op);
        };
    }

    @Override
    public Object execute(
        HonchoOperation op,
        HonchoContext ctx,
        HonchoClient client,
        Object requestBody,
        Map<String, String> pathVars,
        Map<String, ?> queryParams
    ) throws HonchoCallException {
        if (!OPS.contains(op)) {
            throw new HonchoCallException(
                "ConclusionsProviderV3 does not handle " + op, 501, null);
        }
        String substituted = V3ProviderSupport.substitutePath(pathTemplate(op), ctx, pathVars);
        String url = V3ProviderSupport.buildUrl(ctx.baseUrl(), ctx.apiVersion().pathPrefix(), substituted, queryParams);
        try {
            // LIST_PEER_CONCLUSIONS wraps the inbound filter map in Honcho's
            // {filters:{...}} envelope (buildFiltersBody). CREATE_CONCLUSIONS
            // forwards the inbound {conclusions:[...]} body verbatim (we may
            // need to default to an empty list when the UI sends nothing).
            // DELETE_CONCLUSION has no body and no query params.
            Object effectiveBody;
            if (op == HonchoOperation.LIST_PEER_CONCLUSIONS) {
                effectiveBody = buildFiltersBody(requestBody, queryParams, pathVars);
            } else if (op == HonchoOperation.CREATE_CONCLUSIONS) {
                effectiveBody = requestBody != null ? requestBody : Map.of("conclusions", java.util.List.of());
            } else {
                effectiveBody = null;
            }
            var spec = http.method(httpMethod(op))
                .uri(url)
                .headers(h -> V3ProviderSupport.applyAuth(h, ctx))
                .contentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Object> response = (effectiveBody != null)
                ? spec.body(effectiveBody).retrieve().toEntity(Object.class)
                : spec.retrieve().toEntity(Object.class);
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            throw V3ProviderSupport.toHonchoCallException(e);
        } catch (Exception e) {
            throw V3ProviderSupport.transportFailure(ctx.baseUrl(), e);
        }
    }

    /**
     * Wrap the inbound filter map in Honcho's {@code {"filters": {...}}}
     * envelope.
     *
     * <p>If the caller already passed a {@link Map} that has a {@code filters}
     * key, we sanitize it (drop unknown keys, add {@code observed_id}
     * from the path variable). Otherwise treat the caller-supplied map as
     * a flat filter set, sanitize, and fall back on any query params +
     * the {@code {peerId}} path variable (so callers can target a specific
     * peer via {@code ?observed_id=alice}).
     *
     * <p>The whitelist is critical: Honcho v3 returns 422
     * "Column <name> does not exist on Document" for any filter key
     * that is not a real Mongo column on the Conclusion document
     * (observed_id, observer_id, session_id, content, created_at,
     * id). The frontend sometimes sends extra fields like {@code size}
     * or {@code limit} that look like they belong in the filter but
     * actually belong on the PAGE envelope, not in the filter object.
     * Forwarding them to Honcho triggers a confusing 422.
     */
    private static final Set<String> ALLOWED_FILTER_KEYS = Set.of(
        "observed_id",
        "observer_id",
        "session_id",
        "content",
        "created_at_start",
        "created_at_end"
    );

    static Map<String, Object> buildFiltersBody(
        Object requestBody,
        Map<String, ?> queryParams,
        Map<String, String> pathVars
    ) {
        // PreWrapped body: the caller explicitly wrapped the request in
        // Honcho's {filters:{...}} envelope, so they know what they're
        // doing. Pass the inner map through verbatim — the caller is
        // responsible for not adding bogus keys, and we don't want
        // to mutate the caller's explicit shape. observed_id is the
        // only thing we backfill here (from peerId), because it's
        // structurally required by the controller's "POST {} and get a
        // peer's list" contract.
        if (requestBody instanceof Map<?, ?> raw && raw.containsKey("filters")
            && raw.get("filters") instanceof Map<?, ?>) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                if (e.getKey() instanceof String && e.getValue() != null) {
                    out.put((String) e.getKey(), e.getValue());
                }
            }
            if (pathVars != null && pathVars.get("peerId") != null
                && out.get("filters") instanceof Map<?, ?> filtersMap
                && !filtersMap.containsKey("observed_id")) {
                Map<String, Object> newFilters = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) filtersMap).entrySet()) {
                    if (e.getKey() instanceof String && e.getValue() != null) {
                        newFilters.put((String) e.getKey(), e.getValue());
                    }
                }
                newFilters.put("observed_id", pathVars.get("peerId"));
                out.put("filters", newFilters);
            }
            return out;
        }

        // Flat body: this is the case where the bug appeared. Apply the
        // whitelist so callers can pass extra fields (size, limit, page)
        // that the proxy doesn't recognize, without triggering a 422 from
        // Honcho's column-type validation. The whitelist covers all known
        // v3 Conclusion filter columns; unknown keys are silently dropped.
        Map<String, Object> filters = new LinkedHashMap<>();
        java.util.function.Consumer<Map<?, ?>> ingest = (src) -> {
            if (src == null) return;
            for (Map.Entry<?, ?> e : src.entrySet()) {
                if (e.getValue() != null && e.getKey() != null
                    && ALLOWED_FILTER_KEYS.contains(e.getKey().toString())) {
                    filters.put(e.getKey().toString(), e.getValue());
                }
            }
        };

        if (requestBody instanceof Map<?, ?> flat) {
            ingest.accept(flat);
        }
        if (queryParams != null) {
            ingest.accept(queryParams);
        }
        if (pathVars != null && pathVars.get("peerId") != null) {
            // /conclusions is workspace-scoped; the per-peer list is
            // expressed as observed_id = peerId. Fill in if the caller
            // omitted it.
            filters.putIfAbsent("observed_id", pathVars.get("peerId"));
        }
        return Map.of("filters", filters);
    }
}
