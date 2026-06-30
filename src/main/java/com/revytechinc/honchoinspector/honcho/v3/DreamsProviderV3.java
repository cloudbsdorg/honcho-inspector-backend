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
 * V3 provider for scheduling a memory-consolidation dream on a peer.
 *
 * <p>Maps {@link HonchoOperation#SCHEDULE_DREAM} to a
 * {@code POST /v3/workspaces/{ws}/schedule_dream} call. The legacy
 * {@code /api/dream} endpoint took the peer id in the body; v3 reverts
 * to a workspace-scoped endpoint with the peer passed in the body as
 * {@code observer}, so the pathVars map for this op is empty and the
 * observer/observed/session translation happens in
 * {@link HonchoV3Client#scheduleDream} before this provider is invoked.
 */
@Component
public class DreamsProviderV3 implements HonchoProvider {

    private final RestClient http;

    public DreamsProviderV3(RestClient.Builder builder) {
        this.http = builder.build();
    }

    @Override
    public Set<HonchoOperation> operations() {
        return Set.of(HonchoOperation.SCHEDULE_DREAM);
    }

    @Override
    public Set<HonchoApiVersion> supportedVersions() {
        return Set.of(HonchoApiVersion.V3);
    }

    @Override
    public String pathTemplate(HonchoOperation op) {
        return "workspaces/{ws}/schedule_dream";
    }

    @Override
    public HttpMethod httpMethod(HonchoOperation op) {
        return HttpMethod.POST;
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
            ResponseEntity<Object> response = http.post()
                .uri(url)
                .headers(h -> V3ProviderSupport.applyAuth(h, ctx))
                .body(requestBody)
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
