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
 * <h2>Notable v2 → v3 contract changes</h2>
 * <ul>
 *   <li>{@link HonchoOperation#LIST_SESSIONS} is <strong>POST</strong> in
 *   v3 (it was GET in v2) and uses a separate
 *   {@code /workspaces/{ws}/sessions/list} path — the bare
 *   {@code /workspaces/{ws}/sessions} endpoint only accepts
 *   {@code CREATE_SESSION} (POST with a body). Honcho v3 uses POST for
 *   list endpoints so a richer filter / pagination body can be sent.
 *   The {@link HonchoOperation} javadoc documents the same change for
 *   the peer list endpoint.</li>
 *   <li>{@link HonchoOperation#GET_SESSION} has no direct GET endpoint
 *   in v3 — {@code GET /workspaces/{ws}/sessions/{sessionId}} returns
 *   {@code 405 Method Not Allowed} with {@code Allow: PUT} (the only
 *   method registered on that path is the metadata-update PUT). To
 *   fetch a single session's metadata we POST to the
 *   {@code /sessions/list} endpoint with
 *   {@code {filters:{id:"<sessionId>"}}} and unwrap {@code items[0]}.
 *   The dispatcher returns the unwrapped session so callers can keep
 *   treating it as a single-session GET.</li>
 * </ul>
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
            case LIST_SESSIONS -> "workspaces/{ws}/sessions/list";
            case CREATE_SESSION -> "workspaces/{ws}/sessions";
            case DELETE_SESSION -> "workspaces/{ws}/sessions/{sessionId}";
            // GET_SESSION shares LIST_SESSIONS's path so we can use the
            // list-then-filter endpoint to look up a single session.
            case GET_SESSION -> "workspaces/{ws}/sessions/list";
            case GET_SESSION_CONTEXT -> "workspaces/{ws}/sessions/{sessionId}/context";
            case GET_SESSION_SUMMARIES -> "workspaces/{ws}/sessions/{sessionId}/summaries";
            case GET_SESSION_PEERS -> "workspaces/{ws}/sessions/{sessionId}/peers";
            default -> throw new UnsupportedOperationException(
                "SessionsProviderV3 has no path template for " + op);
        };
    }

    @Override
    public HttpMethod httpMethod(HonchoOperation op) {
        return switch (op) {
            // v2→v3 contract change: LIST_SESSIONS is now POST.
            // GET_SESSION also POSTs to the list endpoint with a
            // single-id filter (v3 has no GET-by-id for sessions).
            case LIST_SESSIONS, CREATE_SESSION, GET_SESSION -> HttpMethod.POST;
            case GET_SESSION_CONTEXT,
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
        // GET_SESSION has no GET endpoint in v3 — translate the call to
        // POST /sessions/list with a {filters:{id:"..."}} body and unwrap
        // the single matching item so callers see a session-shaped object.
        Object effectiveBody = requestBody;
        if (op == HonchoOperation.GET_SESSION) {
            String sessionId = pathVars == null ? null : pathVars.get("sessionId");
            if (sessionId == null || sessionId.isBlank()) {
                throw new HonchoCallException(
                    "missing sessionId path variable for GET_SESSION",
                    400,
                    ctx.baseUrl() + "/" + pathTemplate(op)
                );
            }
            effectiveBody = Map.of("filters", Map.of("id", sessionId));
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
            Object body = response.getBody();

            if (op == HonchoOperation.GET_SESSION && body instanceof Map<?, ?> envelope) {
                Object items = envelope.get("items");
                if (items instanceof List<?> list && !list.isEmpty()) {
                    return list.get(0);
                }
                return null;
            }
            return body;
        } catch (HttpStatusCodeException e) {
            throw V3ProviderSupport.toHonchoCallException(e);
        } catch (Exception e) {
            throw V3ProviderSupport.transportFailure(ctx.baseUrl(), e);
        }
    }
}
