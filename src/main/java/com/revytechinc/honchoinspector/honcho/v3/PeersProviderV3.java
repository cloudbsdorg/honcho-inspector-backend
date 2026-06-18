package com.revytechinc.honchoinspector.honcho.v3;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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
 *   <li>{@link HonchoOperation#UPDATE_PEER_CARD} &mdash; <code>POST
 *       /v3/workspaces/{ws}/peers/{peerId}/card</code>.</li>
 *   <li>{@link HonchoOperation#GET_REPRESENTATION} &mdash; <code>GET
 *       /v3/workspaces/{ws}/peers/{peerId}/representation</code>.</li>
 * </ul>
 *
 * <p>Peer <em>query</em> operations (chat, search, conclusions list, sessions
 * list, conclusions query) live in {@link PeerQueryProviderV3} &mdash; they
 * share the same resource prefix but belong to a logically distinct cluster
 * (semantic-search / agentic-query endpoints).
 *
 * <p>Call shape: each {@link #execute} dispatch builds the absolute URI by
 * prepending the profile's {@code baseUrl} to the substituted v3 path, then
 * delegates to the shared {@link #exchange} helper which applies
 * per-profile auth headers, sets {@code Content-Type: application/json},
 * and serializes the request body. Transport failures and non-2xx responses
 * are mapped to {@link HonchoCallException} so callers never see raw Spring
 * exceptions.
 */
@Component
public class PeersProviderV3 implements HonchoProvider {

    private static final Logger log = LoggerFactory.getLogger(PeersProviderV3.class);

    private static final Set<HonchoOperation> OPS = EnumSet.of(
        HonchoOperation.LIST_PEERS,
        HonchoOperation.CREATE_PEER,
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
            case LIST_PEERS                -> "v3/workspaces/{ws}/peers/list";
            case CREATE_PEER               -> "v3/workspaces/{ws}/peers";
            case GET_PEER_CARD, UPDATE_PEER_CARD -> "v3/workspaces/{ws}/peers/{peerId}/card";
            case GET_REPRESENTATION        -> "v3/workspaces/{ws}/peers/{peerId}/representation";
            default -> throw new UnsupportedOperationException(
                "PeersProviderV3 has no path template for " + op);
        };
    }

    @Override
    public HttpMethod httpMethod(HonchoOperation op) {
        return switch (op) {
            case LIST_PEERS, CREATE_PEER, UPDATE_PEER_CARD -> HttpMethod.POST;
            case GET_PEER_CARD, GET_REPRESENTATION         -> HttpMethod.GET;
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
        String template = pathTemplate(op);
        String path = substitutePath(template, ctx, pathVars);
        URI uri = buildUri(ctx, path, queryParams);
        return exchange(httpMethod(op), uri, requestBody, ctx);
    }

    /** Package-private for tests. */
    static String substitutePath(String template, HonchoContext ctx, Map<String, String> pathVars) {
        String result = template.replace("{ws}", ctx.workspaceId());
        if (pathVars != null) {
            for (Map.Entry<String, String> e : pathVars.entrySet()) {
                String key = e.getKey();
                String value = e.getValue() == null ? "" : e.getValue();
                result = result.replace("{" + key + "}", value);
            }
        }
        return result;
    }

    /** Package-private for tests. */
    static URI buildUri(HonchoContext ctx, String path, Map<String, ?> query) {
        String base = sanitizeBase(ctx.baseUrl());
        String fullPath = path.startsWith("/") ? path : "/" + path;
        String suffix = buildQuerySuffix(fullPath, query);
        return URI.create(base + fullPath + suffix);
    }

    private static String buildQuerySuffix(String fullPath, Map<String, ?> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringBuilder qs = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, ?> e : query.entrySet()) {
            if (e.getValue() == null) continue;
            if (first) {
                qs.append(fullPath.contains("?") ? '&' : '?');
                first = false;
            } else {
                qs.append('&');
            }
            qs.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            qs.append('=');
            qs.append(URLEncoder.encode(e.getValue().toString(), StandardCharsets.UTF_8));
        }
        return qs.toString();
    }

    private static String sanitizeBase(String base) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.endsWith("/mcp")) {
            b = b.substring(0, b.length() - 4);
        }
        return b;
    }

    private Object exchange(HttpMethod method, URI uri, Object body, HonchoContext ctx) {
        try {
            var spec = http.method(method)
                .uri(uri)
                .headers(h -> applyAuth(h, ctx))
                .contentType(MediaType.APPLICATION_JSON);

            ResponseEntity<Object> response;
            if (body != null) {
                response = spec.body(body).retrieve().toEntity(Object.class);
            } else {
                response = spec.retrieve().toEntity(Object.class);
            }
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            log.debug("Honcho returned non-2xx for {} {}: {}", method, uri, e.getStatusCode());
            throw new HonchoCallException(
                "Honcho returned " + e.getStatusCode() + ": " + safeBody(e),
                e.getStatusCode().value(),
                safeBody(e)
            );
        } catch (HonchoCallException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Transport failure calling Honcho {} {}: {}", method, uri, e.toString());
            throw new HonchoCallException(
                "Cannot reach Honcho at " + ctx.baseUrl() + ": " + e.getMessage(),
                502,
                null
            );
        }
    }

    private static void applyAuth(HttpHeaders headers, HonchoContext ctx) {
        headers.setBearerAuth(ctx.apiKey());
        headers.set("X-Honcho-User-Name", ctx.userName());
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
    }

    private static String safeBody(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        if (body == null) return "";
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }
}
