package com.revytechinc.honchoinspector.honcho.v3;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.honcho.HonchoClient;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;
import com.revytechinc.honchoinspector.honcho.HonchoProvider;
import com.revytechinc.honchoinspector.model.HonchoContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Honcho v3 provider for the seven session-resource operations:
 * list, create, get, delete, context, summaries, and peers.
 *
 * <p>One multi-operation file rather than seven single-op files — keeps
 * related path templates, auth handling, and error mapping in a single
 * class. See {@link HonchoProvider} for the rationale.
 *
 * <h2>Notable v2 → v3 contract change</h2>
 * {@link HonchoOperation#LIST_SESSIONS} is <strong>POST</strong> in v3 (it
 * was GET in v2). Honcho v3 uses POST for list endpoints so a richer
 * filter / pagination body can be sent. The {@link HonchoOperation} javadoc
 * documents the same change for the peer list endpoint.
 */
@Component
public class SessionsProviderV3 implements HonchoProvider {

    private final RestClient http;

    public SessionsProviderV3(RestClient honchoRestClient) {
        this.http = honchoRestClient;
    }

    @Override
    public Set<HonchoOperation> operations() {
        return EnumSet.of(
            HonchoOperation.LIST_SESSIONS,
            HonchoOperation.CREATE_SESSION,
            HonchoOperation.GET_SESSION,
            HonchoOperation.DELETE_SESSION,
            HonchoOperation.GET_SESSION_CONTEXT,
            HonchoOperation.GET_SESSION_SUMMARIES,
            HonchoOperation.GET_SESSION_PEERS
        );
    }

    @Override
    public Set<HonchoApiVersion> supportedVersions() {
        return EnumSet.of(HonchoApiVersion.V3);
    }

    @Override
    public String pathTemplate(HonchoOperation op) {
        return switch (op) {
            case LIST_SESSIONS, CREATE_SESSION -> "v3/workspaces/{ws}/sessions";
            case GET_SESSION, DELETE_SESSION -> "v3/workspaces/{ws}/sessions/{sessionId}";
            case GET_SESSION_CONTEXT -> "v3/workspaces/{ws}/sessions/{sessionId}/context";
            case GET_SESSION_SUMMARIES -> "v3/workspaces/{ws}/sessions/{sessionId}/summaries";
            case GET_SESSION_PEERS -> "v3/workspaces/{ws}/sessions/{sessionId}/peers";
            default -> throw new UnsupportedOperationException(
                "SessionsProviderV3 has no path template for " + op);
        };
    }

    @Override
    public HttpMethod httpMethod(HonchoOperation op) {
        return switch (op) {
            // v2→v3 contract change: LIST_SESSIONS is now POST.
            case LIST_SESSIONS, CREATE_SESSION -> HttpMethod.POST;
            case GET_SESSION,
                 GET_SESSION_CONTEXT,
                 GET_SESSION_SUMMARIES,
                 GET_SESSION_PEERS -> HttpMethod.GET;
            case DELETE_SESSION -> HttpMethod.DELETE;
            default -> throw new UnsupportedOperationException(
                "SessionsProviderV3 has no HTTP method for " + op);
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
        String url = buildUrl(ctx, op, pathVars);
        HttpMethod method = httpMethod(op);
        try {
            var spec = http.method(method)
                .uri(URI.create(url))
                .headers(h -> applyAuth(h, ctx))
                .contentType(MediaType.APPLICATION_JSON);

            if (queryParams != null && !queryParams.isEmpty()) {
                url = url + (url.contains("?") ? "&" : "?") + encodeQuery(queryParams);
                spec = http.method(method)
                    .uri(URI.create(url))
                    .headers(h -> applyAuth(h, ctx))
                    .contentType(MediaType.APPLICATION_JSON);
            }

            ResponseEntity<Object> response;
            if (requestBody != null) {
                response = spec.body(requestBody).retrieve().toEntity(Object.class);
            } else {
                response = spec.retrieve().toEntity(Object.class);
            }
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            throw new HonchoCallException(
                "Honcho returned " + e.getStatusCode() + ": " + safeBody(e),
                e.getStatusCode().value(),
                safeBody(e)
            );
        } catch (HonchoCallException e) {
            throw e;
        } catch (Exception e) {
            throw new HonchoCallException(
                "Cannot reach Honcho at " + ctx.baseUrl() + ": " + e.getMessage(),
                502,
                null
            );
        }
    }

    /**
     * Build the fully-resolved upstream URL for a given operation. Package
     * private so the unit test can verify URL construction directly without
     * needing to mock the entire {@code RestClient} chain.
     *
     * @param ctx      the per-request Honcho context
     * @param op       the operation whose template drives the URL shape
     * @param pathVars map of path-variable placeholders ({@code ws},
     *                 {@code sessionId}); when a key is missing the value
     *                 falls back to {@code ctx.workspaceId()} for {@code ws}
     *                 and to the empty string for {@code sessionId}
     * @return the absolute URL with placeholders substituted
     */
    String buildUrl(HonchoContext ctx, HonchoOperation op, Map<String, String> pathVars) {
        String base = sanitizeBase(ctx.baseUrl());
        String template = pathTemplate(op);
        String ws = pathVars != null && pathVars.get("ws") != null
            ? pathVars.get("ws")
            : ctx.workspaceId();
        String sessionId = pathVars != null ? pathVars.get("sessionId") : null;
        String path = template
            .replace("{ws}", ws)
            .replace("{sessionId}", sessionId == null ? "" : sessionId);
        return base + "/" + path;
    }

    private void applyAuth(HttpHeaders headers, HonchoContext ctx) {
        headers.setBearerAuth(ctx.apiKey());
        headers.set("X-Honcho-User-Name", ctx.userName());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    }

    private static String sanitizeBase(String base) {
        var b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/mcp")) b = b.substring(0, b.length() - 4);
        return b;
    }

    private static String encodeQuery(Map<String, ?> query) {
        return query.entrySet().stream()
            .filter(e -> e.getValue() != null)
            .map(e -> e.getKey() + "=" + java.net.URLEncoder.encode(
                e.getValue().toString(), java.nio.charset.StandardCharsets.UTF_8))
            .reduce((a, b) -> a + "&" + b)
            .orElse("");
    }

    private static String safeBody(HttpStatusCodeException e) {
        var body = e.getResponseBodyAsString();
        if (body == null) return "";
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }
}
