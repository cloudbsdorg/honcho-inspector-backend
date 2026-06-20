package com.revytechinc.honchoinspector.honcho.v3;

import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.model.HonchoContext;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpStatusCodeException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Package-private plumbing shared by the four single-op V3 providers
 * introduced in T13 (Workspace, QueueStatus, Search, Dreams).
 *
 * <p>Each of the four operations lives in its own provider class because
 * the upstream endpoints are too small to group naturally with the
 * peer / session resources. The HTTP exchange, error-translation, URL
 * building, path-variable substitution, and auth-header conventions
 * are identical across them, so they live here in one place.
 *
 * <p>This is the v3-only counterpart of {@code HonchoProxyService}'s
 * private helpers — when the V3 client (T14) is wired up, both will
 * coexist briefly; the new pipeline is expected to win long-term.
 */
final class V3ProviderSupport {

    static final int BODY_TRUNCATE_LEN = 500;

    private V3ProviderSupport() {
    }

    /**
     * Substitute path variables into the template.
     *
     * <p>Two substitution sources are recognised:
     * <ul>
     *   <li>{@code {ws}} — resolved from {@code pathVars} when present,
     *       falling back to {@code ctx.workspaceId()}.</li>
     *   <li>Any other {@code {name}} — replaced from the
     *       {@code pathVars} map (e.g. {@code peerId} for
     *       {@code /v3/workspaces/{ws}/peers/{peerId}/dreams}).</li>
     * </ul>
     */
    static String substitutePath(String template, HonchoContext ctx, Map<String, String> pathVars) {
        String ws = (pathVars != null && pathVars.get("ws") != null)
            ? pathVars.get("ws")
            : ctx.workspaceId();
        String result = template.replace("{ws}", ws);
        if (pathVars != null) {
            for (Map.Entry<String, String> e : pathVars.entrySet()) {
                if (e.getKey() == null || "ws".equals(e.getKey()) || e.getValue() == null) continue;
                result = result.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        return result;
    }

    /**
     * Build the full upstream URL for a substituted path.
     *
     * <p>Sanitises a trailing slash and the legacy {@code /mcp} suffix on
     * the base URL (mirrors {@code HonchoProxyService.sanitizeBase}),
     * prepends the API-version prefix, and appends an optional query
     * string.
     */
    static String buildUrl(String baseUrl, String versionPrefix, String substitutedPath, Map<String, ?> query) {
        String base = sanitizeBase(baseUrl);
        String path = substitutedPath.startsWith("/") ? substitutedPath : "/" + substitutedPath;
        StringBuilder sb = new StringBuilder(base)
            .append('/')
            .append(versionPrefix)
            .append(path);
        if (query != null && !query.isEmpty()) {
            StringBuilder qs = new StringBuilder();
            for (Map.Entry<String, ?> e : query.entrySet()) {
                if (e.getValue() == null) continue;
                if (qs.length() > 0) qs.append('&');
                qs.append(e.getKey())
                    .append('=')
                    .append(URLEncoder.encode(e.getValue().toString(), StandardCharsets.UTF_8));
            }
            if (qs.length() > 0) {
                sb.append(sb.indexOf("?") >= 0 ? '&' : '?').append(qs);
            }
        }
        return sb.toString();
    }

    /**
     * Apply the per-profile auth headers to an outgoing request.
     *
     * <p>Honcho expects {@code Authorization: Bearer <apiKey>} and
     * {@code X-Honcho-User-Name: <userName>} on every call; missing
     * either of these results in a 401/403 upstream.
     */
    static void applyAuth(HttpHeaders headers, HonchoContext ctx) {
        headers.setBearerAuth(ctx.apiKey());
        headers.set("X-Honcho-User-Name", ctx.userName());
        headers.setAccept(List.of(org.springframework.http.MediaType.APPLICATION_JSON));
    }

    /**
     * Translate a Spring {@link HttpStatusCodeException} into a
     * {@link HonchoCallException} preserving the upstream status and
     * a body truncated to {@value #BODY_TRUNCATE_LEN} characters.
     */
    static HonchoCallException toHonchoCallException(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        if (body == null) body = "";
        if (body.length() > BODY_TRUNCATE_LEN) {
            body = body.substring(0, BODY_TRUNCATE_LEN) + "...";
        }
        return new HonchoCallException(
            "Honcho returned " + e.getStatusCode() + ": " + body,
            e.getStatusCode().value(),
            body
        );
    }

    /**
     * Translate a non-HTTP transport failure (DNS, connect, timeout,
     * TLS) into a {@link HonchoCallException} with status 502.
     */
    static HonchoCallException transportFailure(String baseUrl, Exception e) {
        return new HonchoCallException(
            "Cannot reach Honcho at " + baseUrl + ": " + e.getMessage(),
            502,
            null
        );
    }

    private static String sanitizeBase(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/mcp")) b = b.substring(0, b.length() - 4);
        return b;
    }
}
