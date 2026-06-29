package com.revytechinc.honchoinspector.honcho.v3;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.honcho.HonchoClient;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;
import com.revytechinc.honchoinspector.honcho.HonchoProvider;
import com.revytechinc.honchoinspector.model.HonchoContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Honcho v3 multi-operation provider for the three session-message endpoints.
 *
 * <p>Owns the cluster of operations that read and write the messages that
 * participants of a Honcho session exchange:
 * <ul>
 *   <li>{@link HonchoOperation#LIST_SESSION_MESSAGES} — paginated history
 *       read with a POST+filter body.</li>
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
 * <h2>v2 → v3 contract change (LIST_SESSION_MESSAGES)</h2>
 * The legacy v2 path {@code GET /workspaces/{ws}/sessions/{sessionId}/messages}
 * returns {@code 405 Method Not Allowed} on v3 with
 * {@code Allow: POST}. The only method registered on that path is the
 * batch-create endpoint ({@link HonchoOperation#ADD_MESSAGE}). To list
 * messages we have to POST to the dedicated
 * {@code /messages/list} sub-path with a {@code {filters:{}}} body.
 * This provider now translates {@code LIST_SESSION_MESSAGES} into that
 * POST and the dispatcher's {@link HonchoOperation#LIST_SESSION_MESSAGES}
 * contract stays unchanged for upstream callers.
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

    private final RestClient http;

    public MessagesProviderV3(RestClient honchoRestClient) {
        this.http = honchoRestClient;
    }

    @Override
    public Set<HonchoOperation> operations() {
        return EnumSet.of(
            HonchoOperation.LIST_SESSION_MESSAGES,
            HonchoOperation.ADD_MESSAGE,
            HonchoOperation.UPDATE_MESSAGE,
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
            case LIST_SESSION_MESSAGES -> "workspaces/{ws}/sessions/{sessionId}/messages/list";
            case ADD_MESSAGE -> "workspaces/{ws}/sessions/{sessionId}/messages";
            case UPDATE_MESSAGE -> "workspaces/{ws}/sessions/{sessionId}/messages/{messageId}";
            case SEARCH_SESSION_MESSAGES -> "workspaces/{ws}/sessions/{sessionId}/search";
            default -> throw new UnsupportedOperationException(
                "MessagesProviderV3 has no path template for " + op);
        };
    }

    @Override
    public HttpMethod httpMethod(HonchoOperation op) {
        return switch (op) {
            case LIST_SESSION_MESSAGES, ADD_MESSAGE, SEARCH_SESSION_MESSAGES -> HttpMethod.POST;
            case UPDATE_MESSAGE -> HttpMethod.PUT;
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
        // LIST_SESSION_MESSAGES in v3 has no GET endpoint — translate to
        // POST /messages/list with an empty filter envelope. Upstream
        // callers pass a `filters` map as the request body; the v3 API
        // requires it nested under a `filters` key, so we wrap it here.
        Object effectiveBody = requestBody;
        if (op == HonchoOperation.LIST_SESSION_MESSAGES) {
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
            ResponseEntity<Object> response;
            if (effectiveBody != null) {
                response = spec.body(effectiveBody).retrieve().toEntity(Object.class);
            } else {
                response = spec.retrieve().toEntity(Object.class);
            }
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            throw V3ProviderSupport.toHonchoCallException(e);
        } catch (Exception e) {
            throw V3ProviderSupport.transportFailure(ctx.baseUrl(), e);
        }
    }
}
