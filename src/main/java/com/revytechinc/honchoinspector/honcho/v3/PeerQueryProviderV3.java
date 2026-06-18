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
 * Honcho v3 implementation of the five peer <em>query</em> operations &mdash;
 * the agentic / semantic-search endpoints that share the
 * {@code /v3/workspaces/{ws}/peers/{peerId}/...} prefix but don't manage
 * peer objects themselves.
 *
 * <p>The five operations and their v3 upstream paths are:
 * <ul>
 *   <li>{@link HonchoOperation#PEER_CHAT} &mdash; <code>POST
 *       /v3/workspaces/{ws}/peers/{peerId}/chat</code>.</li>
 *   <li>{@link HonchoOperation#SEARCH_PEERS} &mdash; <code>POST
 *       /v3/workspaces/{ws}/peers/{peerId}/search</code>.</li>
 *   <li>{@link HonchoOperation#LIST_PEER_CONCLUSIONS} &mdash; <code>GET
 *       /v3/workspaces/{ws}/peers/{peerId}/conclusions</code>.</li>
 *   <li>{@link HonchoOperation#LIST_PEER_SESSIONS} &mdash; <code>GET
 *       /v3/workspaces/{ws}/peers/{peerId}/sessions</code>.</li>
 *   <li>{@link HonchoOperation#QUERY_PEER_CONCLUSIONS} &mdash; <code>POST
 *       /v3/workspaces/{ws}/peers/{peerId}/conclusions/query</code>.</li>
 * </ul>
 *
 * <p>Peer <em>CRUD</em> operations (list, create, card, representation) live
 * in {@link PeersProviderV3}. The split keeps the v3 provider set to two
 * files per the "one builder per logical operation, ~7-9 files" directive.
 */
@Component
public class PeerQueryProviderV3 implements HonchoProvider {

    private static final Logger log = LoggerFactory.getLogger(PeerQueryProviderV3.class);

    private static final Set<HonchoOperation> OPS = EnumSet.of(
        HonchoOperation.PEER_CHAT,
        HonchoOperation.SEARCH_PEERS,
        HonchoOperation.LIST_PEER_CONCLUSIONS,
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
            case PEER_CHAT              -> "v3/workspaces/{ws}/peers/{peerId}/chat";
            case SEARCH_PEERS           -> "v3/workspaces/{ws}/peers/{peerId}/search";
            case LIST_PEER_CONCLUSIONS  -> "v3/workspaces/{ws}/peers/{peerId}/conclusions";
            case LIST_PEER_SESSIONS     -> "v3/workspaces/{ws}/peers/{peerId}/sessions";
            case QUERY_PEER_CONCLUSIONS -> "v3/workspaces/{ws}/peers/{peerId}/conclusions/query";
            default -> throw new UnsupportedOperationException(
                "PeerQueryProviderV3 has no path template for " + op);
        };
    }

    @Override
    public HttpMethod httpMethod(HonchoOperation op) {
        return switch (op) {
            case PEER_CHAT, SEARCH_PEERS, QUERY_PEER_CONCLUSIONS -> HttpMethod.POST;
            case LIST_PEER_CONCLUSIONS, LIST_PEER_SESSIONS        -> HttpMethod.GET;
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
