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
 * V3 provider for workspace-wide semantic search.
 *
 * <p>Maps {@link HonchoOperation#SEARCH_MESSAGES} to a
 * {@code POST /v3/workspaces/{ws}/search} call. Note that v3 changed
 * this from GET (the legacy {@code /api/search} used a GET with query
 * parameters) to POST with a JSON body — the plan's v2→v3 contract
 * table explicitly calls this out.
 */
@Component
public class SearchProviderV3 implements HonchoProvider {

    private final RestClient http;

    public SearchProviderV3(RestClient.Builder builder) {
        this.http = builder.build();
    }

    @Override
    public Set<HonchoOperation> operations() {
        return Set.of(HonchoOperation.SEARCH_MESSAGES);
    }

    @Override
    public Set<HonchoApiVersion> supportedVersions() {
        return Set.of(HonchoApiVersion.V3);
    }

    @Override
    public String pathTemplate(HonchoOperation op) {
        return "workspaces/{ws}/search";
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
