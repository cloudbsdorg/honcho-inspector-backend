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
 * <p>This provider owns exactly one operation &mdash;
 * {@link HonchoOperation#LIST_PEER_CONCLUSIONS} &mdash; and is deliberately
 * split out from {@link PeerQueryProviderV3} because the upstream path is
 * at the workspace level, not under {@code /peers/{peerId}/...}.
 *
 * <p>Request body shape (per Honcho v3 OpenAPI):
 * <pre>
 * { "filters": { "observed_id": "&lt;peerId&gt;", "size": 50, ... } }
 * </pre>
 *
 * <p>Response shape: {@code Page<Conclusion>} ({@code items, total, page,
 * size, pages}). The proxy forwards this Honcho envelope to the browser
 * unchanged.
 */
@Component
public class ConclusionsProviderV3 implements HonchoProvider {

    private static final Set<HonchoOperation> OPS = EnumSet.of(
        HonchoOperation.LIST_PEER_CONCLUSIONS
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
            default -> throw new UnsupportedOperationException(
                "ConclusionsProviderV3 has no path template for " + op);
        };
    }

    @Override
    public HttpMethod httpMethod(HonchoOperation op) {
        return switch (op) {
            case LIST_PEER_CONCLUSIONS -> HttpMethod.POST;
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
        Map<String, Object> body = buildFiltersBody(requestBody, queryParams, pathVars);
        try {
            ResponseEntity<Object> response = http.method(httpMethod(op))
                .uri(url)
                .headers(h -> V3ProviderSupport.applyAuth(h, ctx))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Object.class);
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
     * key, use it verbatim. Otherwise treat the caller-supplied map as a
     * flat filter set (the controller's typical path), and fall back on
     * any query params + the {@code {peerId}} path variable (so callers
     * can target a specific peer via {@code ?observed_id=alice}) when the
     * map is absent.
     */
    static Map<String, Object> buildFiltersBody(
        Object requestBody,
        Map<String, ?> queryParams,
        Map<String, String> pathVars
    ) {
        if (requestBody instanceof Map<?, ?> raw && raw.containsKey("filters")
            && raw.get("filters") instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) raw;
            return typed;
        }
        Map<String, Object> filters = new LinkedHashMap<>();
        if (requestBody instanceof Map<?, ?> flat) {
            for (Map.Entry<?, ?> e : flat.entrySet()) {
                if (e.getValue() != null && e.getKey() != null) {
                    filters.put(e.getKey().toString(), e.getValue());
                }
            }
        }
        if (queryParams != null) {
            for (Map.Entry<String, ?> e : queryParams.entrySet()) {
                if (e.getValue() != null) filters.put(e.getKey(), e.getValue());
            }
        }
        if (pathVars != null && pathVars.get("peerId") != null) {
            filters.putIfAbsent("observed_id", pathVars.get("peerId"));
        }
        return Map.of("filters", filters);
    }
}
