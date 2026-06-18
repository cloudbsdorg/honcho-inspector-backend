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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Honcho v3 multi-operation provider for the three session-message endpoints.
 *
 * <p>Owns the cluster of operations that read and write the messages that
 * participants of a Honcho session exchange:
 * <ul>
 *   <li>{@link HonchoOperation#LIST_SESSION_MESSAGES} — paginated history
 *       read with query parameters.</li>
 *   <li>{@link HonchoOperation#ADD_MESSAGE} — append one or more messages
 *       to a session.</li>
 *   <li>{@link HonchoOperation#SEARCH_SESSION_MESSAGES} — semantic search
 *       across the messages of a single session.</li>
 * </ul>
 *
 * <h2>Why one class for three ops</h2>
 * The three endpoints share the same URL root
 * ({@code /v3/workspaces/{ws}/sessions/{sessionId}/...}), the same auth
 * headers, and the same error-mapping rules. Grouping them keeps that
 * cohesion in a single class — splitting one-op-per-file would scatter
 * the dispatch across three nearly-identical files with no reuse.
 *
 * <h2>v2 → v3 contract change</h2>
 * The legacy {@code HonchoController} exposed these as
 * {@code GET /api/sessions/{id}/messages} (list) and
 * {@code POST /api/sessions/{id}/messages} (add). Honcho v3 keeps both
 * paths and adds {@code POST .../search} (workspace-relative search was
 * the only NEW path). {@code LIST_SESSION_MESSAGES} stayed a {@code GET}
 * in v3 — unlike the peer / session list endpoints, which flipped to
 * {@code POST .../list}. This provider preserves that distinction on
 * purpose.
 *
 * <h2>Path templates</h2>
 * Templates are workspace-relative: the dispatcher (built in T15)
 * prepends {@code <apiVersion>/workspaces/<workspaceId>/} at call time.
 * The template therefore starts at the {@code sessions/...} segment.
 *
 * <h2>RestClient strategy</h2>
 * A {@link RestClient.Builder} is injected (Spring Boot auto-configures
 * the bean) and a per-call {@code RestClient} is built with the
 * request's base URL. This keeps the provider stateless with respect to
 * Honcho base URL — different profiles may use different Honcho
 * instances — and lets the unit test substitute a mocked builder.
 */
@Component
public class MessagesProviderV3 implements HonchoProvider {

    private final RestClient.Builder clientBuilder;

    public MessagesProviderV3(RestClient.Builder clientBuilder) {
        this.clientBuilder = clientBuilder;
    }

    @Override
    public Set<HonchoOperation> operations() {
        return EnumSet.of(
            HonchoOperation.LIST_SESSION_MESSAGES,
            HonchoOperation.ADD_MESSAGE,
            HonchoOperation.SEARCH_SESSION_MESSAGES
        );
    }

    @Override
    public Set<HonchoApiVersion> supportedVersions() {
        return EnumSet.of(HonchoApiVersion.V3);
    }

    @Override
    public String pathTemplate(HonchoOperation op) {
        return switch (op) {
            case LIST_SESSION_MESSAGES, ADD_MESSAGE -> "sessions/{sessionId}/messages";
            case SEARCH_SESSION_MESSAGES -> "sessions/{sessionId}/search";
            default -> throw new UnsupportedOperationException(
                "MessagesProviderV3 has no path template for " + op);
        };
    }

    @Override
    public HttpMethod httpMethod(HonchoOperation op) {
        return switch (op) {
            case LIST_SESSION_MESSAGES -> HttpMethod.GET;
            case ADD_MESSAGE -> HttpMethod.POST;
            case SEARCH_SESSION_MESSAGES -> HttpMethod.POST;
            default -> throw new UnsupportedOperationException(
                "MessagesProviderV3 has no HTTP method for " + op);
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
        String sessionId = pathVars == null ? "" : pathVars.getOrDefault("sessionId", "");
        String path = buildWorkspacePath(ctx, pathTemplate(op), Map.of("sessionId", sessionId));
        return switch (op) {
            case LIST_SESSION_MESSAGES ->
                exchange(ctx, HttpMethod.GET, path, queryParams, null);
            case ADD_MESSAGE ->
                exchange(ctx, HttpMethod.POST, path, null, requestBody);
            case SEARCH_SESSION_MESSAGES ->
                exchange(ctx, HttpMethod.POST, path, null, requestBody);
            default -> throw new HonchoCallException(
                "MessagesProviderV3 does not handle " + op, 501, null);
        };
    }

    private String buildWorkspacePath(HonchoContext ctx, String template, Map<String, String> pathVars) {
        String ws = ctx.workspaceId();
        StringBuilder out = new StringBuilder();
        out.append(ctx.apiVersion().pathPrefix())
           .append("/workspaces/").append(ws).append('/');
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("Unterminated '{' in path template: " + template);
                }
                String key = template.substring(i + 1, end);
                String value = pathVars == null ? "" : pathVars.getOrDefault(key, "");
                out.append(value);
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private Object exchange(
        HonchoContext ctx,
        HttpMethod method,
        String path,
        Map<String, ?> query,
        Object body
    ) {
        try {
            RestClient http = clientBuilder.baseUrl(sanitizeBase(ctx.baseUrl())).build();
            var spec = http.method(method)
                .uri(uri(ctx, path, query))
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
            throw new HonchoCallException(
                "Honcho returned " + e.getStatusCode() + ": " + safeBody(e),
                e.getStatusCode().value(),
                safeBody(e)
            );
        } catch (Exception e) {
            throw new HonchoCallException(
                "Cannot reach Honcho at " + ctx.baseUrl() + ": " + e.getMessage(),
                502,
                null
            );
        }
    }

    private URI uri(HonchoContext ctx, String path, Map<String, ?> query) {
        if (query == null || query.isEmpty()) {
            return URI.create(ctx.baseUrl() + "/" + path);
        }
        StringBuilder sb = new StringBuilder(ctx.baseUrl()).append('/').append(path);
        sb.append('?');
        boolean first = true;
        for (Map.Entry<String, ?> e : query.entrySet()) {
            if (e.getValue() == null) continue;
            if (!first) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue().toString(), StandardCharsets.UTF_8));
            first = false;
        }
        return URI.create(sb.toString());
    }

    private void applyAuth(HttpHeaders headers, HonchoContext ctx) {
        headers.setBearerAuth(ctx.apiKey());
        headers.set("X-Honcho-User-Name", ctx.userName());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    }

    private String sanitizeBase(String base) {
        var b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/mcp")) b = b.substring(0, b.length() - 4);
        return b;
    }

    private String safeBody(HttpStatusCodeException e) {
        var body = e.getResponseBodyAsString();
        if (body == null) return "";
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }
}
