package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.auth.repo.AuditLogRepository;
import com.revytechinc.honchoinspector.auth.repo.AuthSessionRepository;

import com.revytechinc.honchoinspector.config.HonchoProperties;
import com.revytechinc.honchoinspector.config.OpenApiConfig;
import com.revytechinc.honchoinspector.filter.SessionAuthFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin maintenance surface: manual triggers for the scheduled jobs
 * (audit retention sweep, expired session sweep) and a status endpoint
 * showing current row counts and retention config. The same code paths
 * the cron job uses are exposed here so an operator can run them on
 * demand without waiting for the next tick.
 */
@RestController
@RequestMapping("/api/admin/maintenance")
@RequireAdmin
@Tag(name = OpenApiConfig.TAG_ADMIN,
    description = "Admin-only maintenance tasks: manual audit purge, manual session sweep, current state.")
public class AdminMaintenanceController {

    private final AuditRetentionJob auditRetention;
    private final AuthSessionRepository sessions;
    private final AuditLogRepository audit;
    private final HonchoProperties properties;
    private final AdminAudit adminAudit;

    public AdminMaintenanceController(
        AuditRetentionJob auditRetention,
        AuthSessionRepository sessions,
        AuditLogRepository audit,
        HonchoProperties properties,
        AdminAudit adminAudit
    ) {
        this.auditRetention = auditRetention;
        this.sessions = sessions;
        this.audit = audit;
        this.properties = properties;
        this.adminAudit = adminAudit;
    }

    @GetMapping("/status")
    @Operation(summary = "Current state: row counts and retention config")
    public ResponseEntity<?> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auditRows", audit.count());
        var cfg = properties.audit();
        m.put("auditRetentionDays", cfg.retentionDays());
        m.put("auditMaxRows", cfg.maxRows());
        m.put("auditPurgeCron", cfg.purgeCron());
        m.put("generatedAt", Instant.now().toString());
        return ResponseEntity.ok(m);
    }

    @PostMapping("/audit/purge")
    @Operation(summary = "Manually trigger the audit retention sweep. Records an 'audit.purge' entry with deleted counts.")
    public ResponseEntity<?> purgeAudit(HttpServletRequest req) {
        var current = currentUser(req);
        var r = auditRetention.run(
            current == null ? null : current.user().id(),
            current == null ? null : current.session().id());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ageDeleted", r.ageDeleted());
        m.put("sizeDeleted", r.sizeDeleted());
        m.put("totalDeleted", r.totalDeleted());
        m.put("retentionDays", r.retentionDays());
        m.put("maxRows", r.maxRows());
        m.put("ranAt", Instant.now().toString());
        return ResponseEntity.ok(m);
    }

    @PostMapping("/sessions/purge-expired")
    @Operation(summary = "Manually sweep expired auth_sessions. Returns the count deleted.")
    public ResponseEntity<?> purgeExpiredSessions(HttpServletRequest req) {
        Instant now = Instant.now();
        int n = sessions.deleteExpired(now);
        var current = currentUser(req);
        adminAudit.record(
            current == null ? null : current.user().id(),
            "sessions.purge",
            null, null,
            clientIp(req),
            current == null ? null : current.session().id(),
            Map.of("deleted", n, "ranAt", now.toString()));
        return ResponseEntity.ok(Map.of("deleted", n, "ranAt", now.toString()));
    }

    private AuthService.CurrentUser currentUser(HttpServletRequest req) {
        return (AuthService.CurrentUser) req.getAttribute(SessionAuthFilter.CURRENT_USER_ATTR);
    }

    private static String clientIp(HttpServletRequest req) {
        var fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
