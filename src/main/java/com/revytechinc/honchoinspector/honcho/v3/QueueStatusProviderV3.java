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
 * V3 provider for the workspace queue-status operation.
 *
 * <p>Maps {@link HonchoOperation#GET_QUEUE_STATUS} to a
 * {@code GET /v3/workspaces/{ws}/queue/status} call. Returns the
 * work-unit counts ({@code total}, {@code completed},
 * {@code in-progress}, {@code pending}) for the workspace's
 * background derivation queue. The upstream Honcho v3 API uses the
 * {@code /queue/status} path (with a slash), confirmed against
 * {@code https://honcho.cloudbsd.org/openapi.json}.
 */
@Component
public class QueueStatusProviderV3 implements HonchoProvider {

    private final RestClient http;

    public QueueStatusProviderV3(RestClient.Builder builder) {
        this.http = builder.build();
    }

    @Override
    public Set<HonchoOperation> operations() {
        return Set.of(HonchoOperation.GET_QUEUE_STATUS);
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
