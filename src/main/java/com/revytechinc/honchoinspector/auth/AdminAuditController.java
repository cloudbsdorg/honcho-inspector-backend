package com.revytechinc.honchoinspector.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.revytechinc.honchoinspector.config.OpenApiConfig;
import com.revytechinc.honchoinspector.model.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/audit")
@RequireAdmin
@Tag(name = OpenApiConfig.TAG_ADMIN,
    description = "Admin-only audit log query. Filter by actor, target, action, time range.")
public class AdminAuditController {

    private final AuditLogDao dao;
    private final ObjectMapper json;

    public AdminAuditController(AuditLogDao dao, ObjectMapper json) {
        this.dao = dao;
        this.json = json;
    }

    @GetMapping
    @Operation(summary = "Query the audit log (most recent first). All filters are optional and AND-combined.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of audit entries"),
        @ApiResponse(responseCode = "400", description = "Invalid 'since' timestamp",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "Caller is not admin",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> list(
        @Parameter(description = "Filter by actor user id (the user who performed the action)")
        @RequestParam(required = false) String actor,
        @Parameter(description = "Filter by target user id (the user the action was performed ON)")
        @RequestParam(required = false) String target,
        @Parameter(description = "Filter by action (e.g. 'user.create', 'user.delete', 'user.sessions.revoke', 'user.password.reset', 'audit.purge', 'sessions.purge')")
        @RequestParam(required = false) String action,
        @Parameter(description = "Filter to entries at or after this ISO-8601 instant", example = "2026-06-01T00:00:00Z")
        @RequestParam(required = false) String since,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") String pageSize
    ) {
        Instant sinceInstant = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceInstant = Instant.parse(since);
            } catch (DateTimeParseException e) {
                return ResponseEntity.badRequest().body(new ErrorResponse("invalid 'since' timestamp: " + since));
            }
        }
        var p = PageSize.parse(pageSize);
        int rows = p.rows;
        int offset = page * rows;
        var q = new AuditLogDao.Query(actor, target, action, sinceInstant);
        var entries = dao.search(q, rows, offset);
        return ResponseEntity.ok(Map.of(
            "items", entries.stream().map(this::toDto).toList(),
            "total", dao.count(),
            "page", page,
            "rows", rows,
            "pages", rows == Integer.MAX_VALUE ? 1 : (int) Math.ceil((double) dao.count() / rows)
        ));
    }

    private Map<String, Object> toDto(AuditLogDao.Entry e) {
        Object metadata = null;
        if (e.metadata() != null && !e.metadata().isBlank()) {
            try {
                metadata = json.readTree(e.metadata());
            } catch (JsonProcessingException ex) {
                metadata = e.metadata();
            }
        }
        return Map.of(
            "id", e.id(),
            "actorUserId", e.actorUserId() == null ? "" : e.actorUserId(),
            "action", e.action(),
            "targetUserId", e.targetUserId() == null ? "" : e.targetUserId(),
            "targetResource", e.targetResource() == null ? "" : e.targetResource(),
            "ip", e.ip() == null ? "" : e.ip(),
            "sessionId", e.sessionId() == null ? "" : e.sessionId(),
            "metadata", metadata == null ? Map.of() : metadata,
            "createdAt", e.createdAt().toString()
        );
    }

    @Schema(name = "AdminAuditList",
        description = "Paginated audit log query result. Entries are most-recent first.",
        example = "{\"items\":[{\"id\":\"a1\",\"actorUserId\":\"u-admin\",\"action\":\"user.create\",\"targetUserId\":\"u-alice\",\"createdAt\":\"2026-06-17T22:00:00Z\"}],\"total\":1,\"page\":0,\"rows\":20,\"pages\":1}")
    public record AuditListDto(List<Map<String, Object>> items, long total, int page, int rows, int pages) {}
}
