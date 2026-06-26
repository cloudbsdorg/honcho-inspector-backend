package com.revytechinc.honchoinspector.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import com.revytechinc.honchoinspector.config.OpenApiConfig;

/**
 * Admin diagnostics surface. Polls the backend's own Spring Boot
 * Actuator endpoints over loopback and re-shapes them into a generic
 * envelope that doesn't leak Spring-specific terminology to the UI.
 *
 * Why this exists: the browser must talk to a single backend port.
 * If we exposed /actuator/* directly to the UI, every deployment
 * behind a reverse proxy (nginx, apache, caddy) would need to
 * explicitly allow the actuator prefix — and operators often don't
 * want /actuator publicly reachable at all. By relaying through
 * /api/admin/diagnostics the actuator stays loopback-only, gated by
 * the admin session, and the URL contract is generic enough that
 * swapping the underlying implementation later (Micrometer,
 * OpenTelemetry, etc.) doesn't require frontend changes.
 */
@RestController
@RequestMapping("/api/admin/diagnostics")
@RequireAdmin
@Tag(name = OpenApiConfig.TAG_ADMIN,
    description = "Admin-only diagnostics: server health, build info, runtime metrics. Generic envelope over the backend's internal instrumentation.")
public class AdminDiagnosticsController {

    private final RestClient.Builder builder;
    private final Environment environment;

    public AdminDiagnosticsController(
        RestClient.Builder builder,
        Environment environment
    ) {
        this.builder = builder;
        this.environment = environment;
    }

    @GetMapping
    @Operation(summary = "Server diagnostics envelope",
        description = "Returns server health, build info, and runtime metrics as a single JSON envelope. " +
            "The backend polls its own internal instrumentation (Spring Boot Actuator by default) and " +
            "shapes the response into a generic format.")
    public ResponseEntity<DiagnosticsEnvelope> get() {
        // Resolve the loopback URL per-request so we always pick up the
        // current port (the test harness sets `local.server.port`
        // AFTER the controller is constructed).
        String address = environment.getProperty("server.address", "127.0.0.1");
        String port = environment.getProperty("local.server.port");
        if (port == null) port = environment.getProperty("server.port", "8080");
        String baseUrl = "http://" + address + ":" + port;
        RestClient client = builder.baseUrl(baseUrl).build();
        DiagnosticsHealth health = fetch(client, "/actuator/health", DiagnosticsHealth.class);
        DiagnosticsBuild build = fetch(client, "/actuator/info", DiagnosticsBuild.class);
        List<String> metricNames = fetch(client, "/actuator/metrics", MetricNamesResponse.class).names();
        List<DiagnosticsMetric> metrics = new ArrayList<>(metricNames.size());
        for (String name : metricNames) {
            // Each metric call is its own HTTP round-trip — that's fine,
            // actuator is local. Could be parallelized if the list grows.
            DiagnosticsMetric m = fetch(client, "/actuator/metrics/" + name, DiagnosticsMetric.class);
            metrics.add(m);
        }
        DiagnosticsEnvelope env = new DiagnosticsEnvelope(health, build, metrics);
        return ResponseEntity.ok(env);
    }

    private static <T> T fetch(RestClient client, String path, Class<T> type) {
        return client.get().uri(path).retrieve().body(type);
    }

    public record DiagnosticsEnvelope(
        DiagnosticsHealth health,
        DiagnosticsBuild build,
        List<DiagnosticsMetric> metrics
    ) {}

    public record DiagnosticsHealth(
        String status,
        List<String> groups
    ) {}

    public record DiagnosticsBuild(
        String artifact,
        String version,
        String buildTime,
        String gitCommit,
        String gitBranch
    ) {}

    public record DiagnosticsMetric(
        String name,
        List<Measurement> measurements,
        String baseUnit,
        String description
    ) {
        public record Measurement(String statistic, double value) {}
    }

    /** Spring's /actuator/metrics returns {names: [...]} — typed for clarity. */
    public record MetricNamesResponse(List<String> names) {}
}
