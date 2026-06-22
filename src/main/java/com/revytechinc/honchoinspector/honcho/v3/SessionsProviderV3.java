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
            case GET_SESSION, DELETE_SESSION -> "workspaces/{ws}/sessions/{sessionId}";
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
        String substituted = V3ProviderSupport.substitutePath(pathTemplate(op), ctx, pathVars);
        String url = V3ProviderSupport.buildUrl(ctx.baseUrl(), ctx.apiVersion().pathPrefix(), substituted, queryParams);
        try {
            var spec = http.method(httpMethod(op))
                .uri(url)
                .headers(h -> V3ProviderSupport.applyAuth(h, ctx))
                .contentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Object> response;
            if (requestBody != null) {
                response = spec.body(requestBody).retrieve().toEntity(Object.class);
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
