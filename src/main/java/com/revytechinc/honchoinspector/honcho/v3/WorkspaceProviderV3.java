package com.revytechinc.honchoinspector.honcho.v3;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.honcho.HonchoClient;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;
import com.revytechinc.honchoinspector.honcho.HonchoProvider;
import com.revytechinc.honchoinspector.model.HonchoContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Set;

/**
 * V3 provider for the single workspace-info operation.
 *
 * <p>Honcho v3 does not expose a {@code GET /v3/workspaces/{id}} endpoint
 * (the path only accepts {@code PUT} and {@code DELETE}). As a
 * connectivity probe we instead hit the queue-status endpoint, which
 * is GET, requires no body, and returns real workspace-scoped data.
 * The {@code HonchoController.workspaceInfo} composite endpoint
 * synthesizes the {@code workspace} field from the context so callers
 * see the workspace ID alongside the live queue snapshot.
 *
 * <p>Kept as a single-op provider because the upstream endpoint is too
 * small to group naturally with the peer or session resource clusters —
 * splitting it that way would force a "Workspace" provider with just
 * one trivial call.
 *
 * <p>All other Honcho provider conventions (auth headers, error
 * translation, URL building) are delegated to
 * {@link V3ProviderSupport}.
 */
@Component
public class WorkspaceProviderV3 implements HonchoProvider {

    private final RestClient http;

    public WorkspaceProviderV3(RestClient.Builder builder) {
        this.http = builder.build();
    }

    @Override
    public Set<HonchoOperation> operations() {
        return Set.of(HonchoOperation.GET_WORKSPACE_INFO);
    }

    @Override
    public Set<HonchoApiVersion> supportedVersions() {
        return Set.of(HonchoApiVersion.V3);
    }

    @Override
    public String pathTemplate(HonchoOperation op) {
        return "workspaces/{ws}/queue/status";
    }

    @Override
    public HttpMethod httpMethod(HonchoOperation op) {
        return HttpMethod.GET;
    }

    @Override
    public Object execute(
        HonchoOperation op,
        HonchoContext ctx,
        HonchoClient client,
        Object requestBody,
        Map<String, String> pathVars,
        Map<String, ?> queryParams
    ) {
        String substituted = V3ProviderSupport.substitutePath(pathTemplate(op), ctx, pathVars);
        String url = V3ProviderSupport.buildUrl(ctx.baseUrl(), ctx.apiVersion().pathPrefix(), substituted, queryParams);
        try {
            ResponseEntity<Object> response = http.get()
                .uri(url)
                .headers(h -> V3ProviderSupport.applyAuth(h, ctx))
                .retrieve()
                .toEntity(Object.class);
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            throw V3ProviderSupport.toHonchoCallException(e);
        } catch (Exception e) {
            throw V3ProviderSupport.transportFailure(ctx.baseUrl(), e);
        }
    }
}
