package com.revytechinc.honchoinspector.service;

import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.model.HonchoContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Service
public class HonchoProxyService {

    private final RestClient http;
    private final String apiVersion;

    public HonchoProxyService(
        RestClient honchoRestClient,
        @Value("${honcho.api-version:v3}") String apiVersion
    ) {
        this.http = honchoRestClient;
        this.apiVersion = apiVersion;
    }

    public Object get(HonchoContext ctx, String path, Map<String, ?> query) {
        return exchange(ctx, HttpMethod.GET, path, query, null);
    }

    public Object post(HonchoContext ctx, String path, Map<String, ?> query, Object body) {
        return exchange(ctx, HttpMethod.POST, path, query, body);
    }

    public Object put(HonchoContext ctx, String path, Map<String, ?> query, Object body) {
        return exchange(ctx, HttpMethod.PUT, path, query, body);
    }

    public Object delete(HonchoContext ctx, String path) {
        return exchange(ctx, HttpMethod.DELETE, path, null, null);
    }

    public void testConnection(HonchoContext ctx) {
        try {
            http.get()
                .uri(uri(ctx, "/" + apiVersion + "/workspaces/" + ctx.workspaceId(), null))
                .headers(h -> applyAuth(h, ctx))
                .retrieve()
                .toBodilessEntity();
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

    private Object exchange(HonchoContext ctx, HttpMethod method, String path, Map<String, ?> query, Object body) {
        try {
            var spec = http.method(method.spring())
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
        var base = sanitizeBase(ctx.baseUrl());
        var fullPath = path.startsWith("/") ? path : "/" + path;
        var builder = URI.create(base + fullPath);
        if (query != null && !query.isEmpty()) {
            var qs = query.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> e.getKey() + "=" + encode(e.getValue().toString()))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
            if (!qs.isEmpty()) {
                var sep = fullPath.contains("?") ? "&" : "?";
                builder = URI.create(base + fullPath + sep + qs);
            }
        }
        return builder;
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

    private String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String safeBody(HttpStatusCodeException e) {
        var body = e.getResponseBodyAsString();
        if (body == null) return "";
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }

    public enum HttpMethod {
        GET, POST, PUT, DELETE;
        public org.springframework.http.HttpMethod spring() {
            return switch (this) {
                case GET -> org.springframework.http.HttpMethod.GET;
                case POST -> org.springframework.http.HttpMethod.POST;
                case PUT -> org.springframework.http.HttpMethod.PUT;
                case DELETE -> org.springframework.http.HttpMethod.DELETE;
            };
        }
    }
}
