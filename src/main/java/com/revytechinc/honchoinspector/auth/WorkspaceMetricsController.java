package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.config.HonchoProperties;
import com.revytechinc.honchoinspector.config.OpenApiConfig;
import com.revytechinc.honchoinspector.filter.SessionAuthFilter;
import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoClientFactory;
import com.revytechinc.honchoinspector.honcho.HonchoMetrics;
import com.revytechinc.honchoinspector.model.HonchoContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workspace-level aggregate counters surfaced to the UI's dashboard
 * "all-time count" KPI cards.
 *
 * <p><b>Why this is NOT admin-gated:</b> every value exposed here is
 * either (a) an in-process proxy counter
 * ({@code honcho.inspector.searches},
 * {@code honcho.inspector.dreams.scheduled},
 * {@code honcho.inspector.messages.sent},
 * {@code honcho.inspector.peers.listed},
 * {@code honcho.inspector.sessions.listed},
 * {@code honcho.inspector.profiles.tested}) or (b) a workspace aggregate
 * ({@code workspace.messageCount}) that is already reachable by any
 * authenticated user. The peer and session counts are visible to every
 * signed-in user via {@code GET /api/peers} and {@code GET /api/sessions};
 * the workspace message total is sourced LIVE from Honcho using the
 * caller's own profile credentials (it is not admin data). The proxy
 * counters reflect what this backend instance has seen during the
 * current process — the same data any user indirectly affects by using
 * the app.
 *
 * <p>The previous home for this endpoint, {@code AdminMetricsController},
 * carried a class-level {@code @RequireAdmin} that gated it for
 * non-admin users. That gate was unjustified: a non-admin user who
 * lands on the dashboard's home view (guarded only by {@code authGuard},
 * not {@code adminGuard}) was hit with HTTP 403 on
 * {@code metrics.service.load()} the moment the dashboard bootstrap ran.
 * The class was renamed and the gate removed; the URL
 * {@code /api/admin/metrics/counters} is preserved for backward
 * compatibility with existing clients.
 *
 * <p>Other admin endpoints (user management, audit log, dashboard
 * aggregates, maintenance) remain gated by {@code @RequireAdmin}; this
 * controller is the one exception, and it exists precisely because the
 * values it surfaces are workspace-level aggregates that already
 * belong to any authenticated user.
 *
 * <p>The seven counters and their single source of truth ({@link HonchoMetrics})
 * are documented in detail on {@link HonchoMetrics}.
 */
@RestController
@RequestMapping("/api/admin/metrics")
@Tag(name = OpenApiConfig.TAG_ADMIN,
    description = "Workspace-level aggregate counters used by the dashboard's KPI strip: six honcho.inspector.* proxy counters plus the Honcho-sourced per-workspace message total. Sourced directly from the in-memory Micrometer registry for the proxy counters (no /actuator loopback); workspace.messageCount is sourced LIVE from Honcho using the caller's own profile credentials (60s cache, falls back to 0 when no profile is available). Authenticated users only; not admin-gated because the values are workspace aggregates already reachable via /api/peers, /api/sessions, and the Honcho proxy.")
public class WorkspaceMetricsController {

    public static final String PROFILE_HEADER = "X-Honcho-Profile-Id";

    private final HonchoMetrics metrics;
    private final ProfileService profiles;
    private final HonchoProperties properties;

    public WorkspaceMetricsController(
        HonchoMetrics metrics,
        ProfileService profiles,
        HonchoProperties properties
    ) {
        this.metrics = metrics;
        this.profiles = profiles;
        this.properties = properties;
    }

    /**
     * Return the seven first-class counters the dashboard's
     * "all-time count" KPI cards read, plus the server clock time
     * at which the snapshot was taken.
     *
     * <p>The key order in the response is fixed
     * ({@code searches, dreams.scheduled, messages.sent,
     * peers.listed, sessions.listed, profiles.tested,
     * workspace.messageCount}) so the dashboard's stat-card layout
     * lines up with the JSON the backend emits. A {@link LinkedHashMap}
     * preserves insertion order; Jackson honors it.
     *
     * <p>{@code workspace.messageCount} is profile-scoped: the
     * dashboard always sends the
     * {@value #PROFILE_HEADER} header on counter
     * fetches, and we use it to build the {@link HonchoContext} we
     * forward to Honcho. If the header is absent we fall back to
     * the calling user's first profile — without a profile we
     * have no Honcho credentials to query, so the value is left at
     * {@code 0}. Both behaviors keep the endpoint robust under the
     * boot-time loadAdmin pattern where the frontend may race a
     * profile-less ping before its profile selector finishes loading.
     */
    @GetMapping("/counters")
    @Operation(
        summary = "First-class counter snapshot for the dashboard",
        description = "Returns the six honcho.inspector.* proxy counters plus workspace.messageCount (Honcho-sourced live total across every session in the active profile's workspace). " +
            "Tagged proxy counters (dreams, messages) are returned as the SUM across every distinct tag value. " +
            "Proxy counters are sourced directly from the in-memory MeterRegistry via HonchoMetrics — no actuator loopback. " +
            "The workspace message total honors X-Honcho-Profile-Id, defaulting to the calling user's first profile when the header is absent. " +
            "Any authenticated user can call this endpoint (the values are workspace-level aggregates, not admin data)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Counter snapshot { counters: { ... }, capturedAt: ISO-8601 }"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid session id",
            content = @io.swagger.v3.oas.annotations.media.Content(
                schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = com.revytechinc.honchoinspector.model.ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, Object>> counters(HttpServletRequest req) {
        // LinkedHashMap so the JSON key order matches the dashboard's
        // stat-card order. Jackson honors LinkedHashMap insertion
        // order for Object-valued maps.
        Map<String, Double> counters = new LinkedHashMap<>();
        counters.put(HonchoMetrics.SEARCHES_COUNTER_NAME, metrics.searchesCounter().count());
        counters.put(HonchoMetrics.DREAMS_COUNTER_NAME, metrics.dreamsCounterTotal());
        counters.put(HonchoMetrics.MESSAGES_COUNTER_NAME, metrics.messagesCounterTotal());
        counters.put(HonchoMetrics.PEERS_LISTED_COUNTER_NAME, metrics.peersListedCounter().count());
        counters.put(HonchoMetrics.SESSIONS_LISTED_COUNTER_NAME, metrics.sessionsListedCounter().count());
        counters.put(HonchoMetrics.PROFILES_TESTED_COUNTER_NAME, metrics.profilesTestedCounter().count());
        counters.put(HonchoMetrics.WORKSPACE_MESSAGE_COUNT_NAME, resolveWorkspaceMessageCount(req));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("counters", counters);
        // Matches the AdminDashboardService.overview() pattern: ISO-8601
        // UTC instant string, no Z-suffix parsing, no timezone surprises.
        body.put("capturedAt", Instant.now().toString());
        return ResponseEntity.ok(body);
    }

    /**
     * Resolve the {@link HonchoMetrics#WORKSPACE_MESSAGE_COUNT_NAME}
     * for the active profile.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>The {@value #PROFILE_HEADER} header (the dashboard
     *       always sets it on counter fetches).</li>
     *   <li>The calling user's first profile
     *       ({@link ProfileService#list(String)}), so a profile-less
     *       ping at boot still gets a plausible number.</li>
     *   <li>Neither: {@code 0.0} — we have no credentials so a call
     *       to Honcho would 401 anyway.</li>
     * </ol>
     *
     * <p>The {@link HonchoMetrics#workspaceMessageCount(String,
     * HonchoContext)} accessor handles the actual Honcho fan-out
     * and the 60-second per-profile cache; this method only
     * resolves the {@link HonchoContext} to pass in. On failure
     * (the user has profiles but every one is unreachable) the
     * accessor returns the stale cache value or 0 — the dashboard
     * never sees a 5xx from this endpoint.
     */
    private double resolveWorkspaceMessageCount(HttpServletRequest req) {
        AuthService.CurrentUser current = (AuthService.CurrentUser) req.getAttribute(SessionAuthFilter.CURRENT_USER_ATTR);
        if (current == null || current.user() == null) {
            return 0.0;
        }
        String userId = current.user().id();
        String headerProfileId = req.getHeader(PROFILE_HEADER);
        ProfileService.ProfileWithKey resolved = null;
        String resolvedProfileId = null;
        if (headerProfileId != null && !headerProfileId.isBlank()) {
            resolved = profiles.getWithKey(userId, headerProfileId).orElse(null);
            if (resolved != null) {
                resolvedProfileId = headerProfileId;
            }
        }
        if (resolved == null) {
            // Fall back to the first profile the user owns. We don't
            // need to sort; the order in which ProfileService returns
            // profiles is stable across calls (the underlying SQLite
            // query has no ORDER BY but the first row for a user is
            // consistent enough for a dashboard fallback).
            List<Profile> userProfiles = profiles.list(userId);
            for (Profile p : userProfiles) {
                var pwk = profiles.getWithKey(userId, p.id()).orElse(null);
                if (pwk != null) {
                    resolved = pwk;
                    resolvedProfileId = p.id();
                    break;
                }
            }
        }
        if (resolved == null) {
            return 0.0;
        }
        HonchoApiVersion apiVersion = HonchoClientFactory.resolveVersion(
            resolved.profile().apiVersion(),
            HonchoApiVersion.fromString(properties.apiVersion())
        );
        HonchoContext ctx = new HonchoContext(
            resolved.apiKey(),
            resolved.profile().baseUrl(),
            resolved.profile().workspaceId(),
            resolved.profile().honchoUserName(),
            apiVersion,
            resolvedProfileId
        );
        return metrics.workspaceMessageCount(resolvedProfileId, ctx);
    }
}
