package com.revytechinc.honchoinspector.honcho.v3;

import java.util.EnumSet;
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
 * Honcho v3 implementation of the four peer <em>query</em> operations &mdash;
 * the agentic / semantic-search endpoints that share the
 * {@code /v3/workspaces/{ws}/peers/{peerId}/...} prefix but don't manage
 * peer objects themselves.
 *
 * <p>The four operations and their v3 upstream paths are:
 * <ul>
 *   <li>{@link HonchoOperation#PEER_CHAT} &mdash; <code>POST
 *       /v3/workspaces/{ws}/peers/{peerId}/chat</code>.</li>
 *   <li>{@link HonchoOperation#SEARCH_PEERS} &mdash; <code>POST
 *       /v3/workspaces/{ws}/peers/{peerId}/search</code>.</li>
 *   <li>{@link HonchoOperation#LIST_PEER_SESSIONS} &mdash; <code>POST
 *       /v3/workspaces/{ws}/peers/{peerId}/sessions</code> with a
 *       {@code {filters:{...}}} body (v3 has no GET endpoint for this;
 *       {@code GET} returns 405 with {@code Allow: POST}).</li>
 *   <li>{@link HonchoOperation#QUERY_PEER_CONCLUSIONS} &mdash; <code>POST
 *       /v3/workspaces/{ws}/peers/{peerId}/conclusions/query</code>.</li>
 * </ul>
 *
 * <p>{@link HonchoOperation#LIST_PEER_CONCLUSIONS} moved to
 * {@link ConclusionsProviderV3} because Honcho v3 exposes it at the
 * workspace level (POST {@code /v3/workspaces/{ws}/conclusions/list})
 * with the peer filter as a body field rather than a path segment.
 *
 * <p>Peer <em>CRUD</em> operations (list, create, card, representation) live
 * in {@link PeersProviderV3}. The split keeps each provider focused on a
 * single resource cluster.
 *
 * <p>All plumbing (path-variable substitution, URL building, auth headers,
 * error translation) is delegated to {@link V3ProviderSupport}.
 */
@Component
public class PeerQueryProviderV3 implements HonchoProvider {

    private static final Set<HonchoOperation> OPS = EnumSet.of(
        HonchoOperation.PEER_CHAT,
        HonchoOperation.SEARCH_PEERS,
        HonchoOperation.LIST_PEER_SESSIONS,
        HonchoOperation.QUERY_PEER_CONCLUSIONS
    );

    private final RestClient http;

    public PeerQueryProviderV3(RestClient honchoRestClient) {
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
            case PEER_CHAT              -> "workspaces/{ws}/peers/{peerId}/chat";
            case SEARCH_PEERS           -> "workspaces/{ws}/peers/{peerId}/search";
            case LIST_PEER_SESSIONS     -> "workspaces/{ws}/peers/{peerId}/sessions";
            case QUERY_PEER_CONCLUSIONS -> "workspaces/{ws}/peers/{peerId}/conclusions/query";
            default -> throw new UnsupportedOperationException(
                "PeerQueryProviderV3 has no path template for " + op);
        };
    }

    @Override
    public HttpMethod httpMethod(HonchoOperation op) {
        return switch (op) {
            case PEER_CHAT, SEARCH_PEERS, QUERY_PEER_CONCLUSIONS, LIST_PEER_SESSIONS -> HttpMethod.POST;
            default -> throw new UnsupportedOperationException(
                "PeerQueryProviderV3 has no HTTP method for " + op);
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
                "PeerQueryProviderV3 does not handle " + op, 501, null);
        }
        // LIST_PEER_SESSIONS in v3 has no GET endpoint. We POST the
        // /peers/{id}/sessions path with {filters:{...}} as the body.
        // The dispatcher passes `filters` in as requestBody (it was
        // originally a queryParams arg, which Honcho ignored).
        Object effectiveBody = requestBody;
        if (op == HonchoOperation.LIST_PEER_SESSIONS) {
            Map<String, ?> filterMap = requestBody instanceof Map ? (Map<String, ?>) requestBody : Map.of();
            effectiveBody = Map.of("filters", filterMap);
        }
        String substituted = V3ProviderSupport.substitutePath(pathTemplate(op), ctx, pathVars);
        String url = V3ProviderSupport.buildUrl(ctx.baseUrl(), ctx.apiVersion().pathPrefix(), substituted, queryParams);
        try {
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
}
