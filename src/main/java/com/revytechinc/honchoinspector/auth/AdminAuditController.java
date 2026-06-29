package com.revytechinc.honchoinspector.auth;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.revytechinc.honchoinspector.auth.repo.AuditLogRepository;
import com.revytechinc.honchoinspector.auth.repo.AuditLogSpecifications;
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

    private final AuditLogRepository repo;
    private final ObjectMapper json;

    public AdminAuditController(AuditLogRepository repo, ObjectMapper json) {
        this.repo = repo;
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
        @Parameter(description = "Filter by action (e.g. 'user.create', 'user.delete', 'conclusion.create', 'conclusion.delete', 'peer.update', 'peer_card.update', 'session.update', 'session.delete', 'message.update')")
        @RequestParam(required = false) String action,
        @Parameter(description = "Filter by Honcho resource type (matches `target_resource` starting with `<resource>:` — e.g. `conclusion` matches `conclusion:abc123`). Combine with `id` for an exact resource match.")
        @RequestParam(required = false) String resource,
        @Parameter(description = "Optional resource id to combine with `resource` for an exact `target_resource = <resource>:<id>` match. When supplied without `resource`, matched exactly.")
        @RequestParam(required = false) String id,
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
        var spec = AuditLogSpecifications.all(action, actor, target, resource, id, sinceInstant);
        var pageResult = repo.findAll(
            spec,
            org.springframework.data.domain.PageRequest.of(
                Math.max(0, offset / Math.max(rows, 1)), Math.max(rows, 1)));
        long total = pageResult.getTotalElements();
        return ResponseEntity.ok(Map.of(
            "items", pageResult.getContent().stream().map(this::toDto).toList(),
            "total", total,
            "page", page,
            "rows", rows,
            "pages", rows == Integer.MAX_VALUE ? 1 : (int) Math.ceil((double) total / rows)
        ));
    }

    private Map<String, Object> toDto(com.revytechinc.honchoinspector.auth.entity.AuditLogEntity e) {
        Object metadata = null;
        if (e.getMetadata() != null && !e.getMetadata().isBlank()) {
            try {
                metadata = json.readTree(e.getMetadata());
            } catch (JacksonException ex) {
                metadata = e.getMetadata();
            }
        }
        return Map.of(
            "id", e.getId(),
            "actorUserId", e.getActorUserId() == null ? "" : e.getActorUserId(),
            "action", e.getAction(),
            "targetUserId", e.getTargetUserId() == null ? "" : e.getTargetUserId(),
            "targetResource", e.getTargetResource() == null ? "" : e.getTargetResource(),
            "ip", e.getIp() == null ? "" : e.getIp(),
            "sessionId", e.getSessionId() == null ? "" : e.getSessionId(),
            "metadata", metadata == null ? Map.of() : metadata,
            "createdAt", e.getCreatedAtAsInstant().toString()
        );
    }

    @Schema(name = "AdminAuditList",
        description = "Paginated audit log query result. Entries are most-recent first.",
        example = "{\"items\":[{\"id\":\"a1\",\"actorUserId\":\"u-admin\",\"action\":\"user.create\",\"targetUserId\":\"u-alice\",\"createdAt\":\"2026-06-17T22:00:00Z\"}],\"total\":1,\"page\":0,\"rows\":20,\"pages\":1}")
    public record AuditListDto(List<Map<String, Object>> items, long total, int page, int rows, int pages) {}
}
