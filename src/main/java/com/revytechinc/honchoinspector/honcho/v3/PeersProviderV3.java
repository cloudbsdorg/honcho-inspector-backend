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
 * Honcho v3 implementation of the five {@code peers}-resource operations that
 * manage the peer objects themselves: list, create, fetch / replace a peer
 * card, and fetch a peer's representation.
 *
 * <p>The five operations and their v3 upstream paths are:
 * <ul>
 *   <li>{@link HonchoOperation#LIST_PEERS} &mdash; <code>POST
 *       /v3/workspaces/{ws}/peers/list</code> (was GET in v2).</li>
 *   <li>{@link HonchoOperation#CREATE_PEER} &mdash; <code>POST
 *       /v3/workspaces/{ws}/peers</code>.</li>
 *   <li>{@link HonchoOperation#GET_PEER_CARD} &mdash; <code>GET
 *       /v3/workspaces/{ws}/peers/{peerId}/card</code>.</li>
 *   <li>{@link HonchoOperation#UPDATE_PEER_CARD} &mdash; <code>PUT
 *       /v3/workspaces/{ws}/peers/{peerId}/card</code>.</li>
 *   <li>{@link HonchoOperation#GET_REPRESENTATION} &mdash; <code>POST
 *       /v3/workspaces/{ws}/peers/{peerId}/representation</code> with an
 *       empty {@code {}} body (Honcho v3 disallows GET on this endpoint).</li>
 * </ul>
 *
 * <p>Peer <em>query</em> operations (chat, search, sessions list, conclusions
 * query) live in {@link PeerQueryProviderV3}; the workspace-level
 * {@code /conclusions/list} lives in {@link ConclusionsProviderV3}. The split
 * keeps each provider focused on a single resource cluster.
 *
 * <p>All plumbing (path-variable substitution, URL building, auth headers,
 * error translation) is delegated to {@link V3ProviderSupport}.
 */
@Component
public class PeersProviderV3 implements HonchoProvider {

    private static final Set<HonchoOperation> OPS = EnumSet.of(
        HonchoOperation.LIST_PEERS,
        HonchoOperation.CREATE_PEER,
        HonchoOperation.UPDATE_PEER,
        HonchoOperation.GET_PEER_CARD,
        HonchoOperation.UPDATE_PEER_CARD,
        HonchoOperation.GET_REPRESENTATION
    );

    private final RestClient http;

    public PeersProviderV3(RestClient honchoRestClient) {
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
            case LIST_PEERS                -> "workspaces/{ws}/peers/list";
            case CREATE_PEER               -> "workspaces/{ws}/peers";
            case UPDATE_PEER               -> "workspaces/{ws}/peers/{peerId}";
            case GET_PEER_CARD, UPDATE_PEER_CARD -> "workspaces/{ws}/peers/{peerId}/card";
            case GET_REPRESENTATION        -> "workspaces/{ws}/peers/{peerId}/representation";
            default -> throw new UnsupportedOperationException(
                "PeersProviderV3 has no path template for " + op);
        };
    }

    @Override
    public HttpMethod httpMethod(HonchoOperation op) {
        return switch (op) {
            case LIST_PEERS, CREATE_PEER, GET_REPRESENTATION -> HttpMethod.POST;
            case UPDATE_PEER, UPDATE_PEER_CARD               -> HttpMethod.PUT;
            case GET_PEER_CARD                                -> HttpMethod.GET;
            default -> throw new UnsupportedOperationException(
                "PeersProviderV3 has no HTTP method for " + op);
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
                "PeersProviderV3 does not handle " + op, 501, null);
        }
        String substituted = V3ProviderSupport.substitutePath(pathTemplate(op), ctx, pathVars);
        String url = V3ProviderSupport.buildUrl(ctx.baseUrl(), ctx.apiVersion().pathPrefix(), substituted, queryParams);
        try {
            var spec = http.method(httpMethod(op))
                .uri(url)
                .headers(h -> V3ProviderSupport.applyAuth(h, ctx))
                .contentType(MediaType.APPLICATION_JSON);
            Object body = (requestBody != null) ? requestBody : defaultBody(op);
            ResponseEntity<Object> response = spec.body(body).retrieve().toEntity(Object.class);
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            throw V3ProviderSupport.toHonchoCallException(e);
        } catch (Exception e) {
            throw V3ProviderSupport.transportFailure(ctx.baseUrl(), e);
        }
    }

    /**
     * Per-operation default body for POST endpoints whose controller
     * doesn't supply one. Honcho v3 requires a JSON body on every POST
     * even when all options are absent; sending an empty JSON object
     * keeps the contract satisfied without forcing every controller to
     * remember. (An empty string literal would serialize as an invalid
     * JSON body and Honcho would 422 on missing fields like id/filters.)
     */
    private static Object defaultBody(HonchoOperation op) {
        return switch (op) {
            case GET_REPRESENTATION -> java.util.Map.of();
            default -> java.util.Map.of();
        };
    }
}
