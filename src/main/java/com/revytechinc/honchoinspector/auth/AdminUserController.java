package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.config.OpenApiConfig;
import com.revytechinc.honchoinspector.filter.SessionAuthFilter;
import com.revytechinc.honchoinspector.model.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
@RequireAdmin
@Tag(name = OpenApiConfig.TAG_ADMIN,
    description = "Admin-only user management. Create, list, update, and delete users; assign admin roles; reset passwords; revoke sessions. Search across username, firstname, lastname, email.")
public class AdminUserController {

    private final AdminUserService admin;

    public AdminUserController(AdminUserService admin) {
        this.admin = admin;
    }

    @GetMapping
    @Operation(summary = "List users (paginated)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of users"),
        @ApiResponse(responseCode = "403", description = "Caller is not admin",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> list(
        @Parameter(description = "Page index (0-based)", example = "0")
        @RequestParam(defaultValue = "0") int page,
        @Parameter(description = "Page size: 10, 20, 30, or ALL", example = "20")
        @RequestParam(defaultValue = "20") String pageSize
    ) {
        var p = PageSize.parse(pageSize);
        return ResponseEntity.ok(admin.list(page, p));
    }

    @GetMapping("/search")
    @Operation(summary = "Search users by username, firstname, lastname, or email (case-insensitive substring match)")
    public ResponseEntity<?> search(
        @Parameter(description = "Search query (empty lists all)", example = "alice")
        @RequestParam(defaultValue = "") String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") String pageSize
    ) {
        var p = PageSize.parse(pageSize);
        return ResponseEntity.ok(admin.search(q, page, p));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single user")
    public ResponseEntity<?> get(@PathVariable String id) {
        var u = admin.get(id);
        if (u == null) return ResponseEntity.status(404).body(new ErrorResponse("user not found"));
        return ResponseEntity.ok(UserResponse.from(u));
    }

    @PostMapping
    @Operation(summary = "Create a new user with explicit role")
    public ResponseEntity<?> create(HttpServletRequest req, @RequestBody CreateUserDto body) {
        var current = currentUser(req);
        if (body == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("body required"));
        }
        var r = admin.create(
            current.user().id(), clientIp(req), sessionId(req),
            body.username(), body.password(),
            body.firstname(), body.lastname(), body.email(),
            body.isAdmin() != null && body.isAdmin());
        if (!r.success()) {
            int status = r.kind() == AdminUserService.ErrorKind.VALIDATION ? 400 : 409;
            return ResponseEntity.status(status).body(new ErrorResponse(r.error()));
        }
        return ResponseEntity.status(201).body(UserResponse.from(r.user()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a user (username, names, email, role). Omitted fields are unchanged.")
    public ResponseEntity<?> update(HttpServletRequest req, @PathVariable String id, @RequestBody UpdateUserDto body) {
        var current = currentUser(req);
        if (body == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("body required"));
        }
        var r = admin.update(
            current.user().id(), clientIp(req), sessionId(req),
            id, body.username(), body.firstname(), body.lastname(), body.email(), body.isAdmin());
        if (!r.found()) return ResponseEntity.status(404).body(new ErrorResponse("user not found"));
        if (r.error() != null) return ResponseEntity.status(409).body(new ErrorResponse(r.error()));
        return ResponseEntity.ok(UserResponse.from(r.user()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a user. CASCADE removes their profiles and sessions.")
    public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
        var current = currentUser(req);
        var r = admin.delete(current.user().id(), clientIp(req), sessionId(req), id);
        if (!r.found()) return ResponseEntity.status(404).body(new ErrorResponse("user not found"));
        if (r.error() != null) return ResponseEntity.status(409).body(new ErrorResponse(r.error()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/sessions")
    @Operation(summary = "List a user's auth sessions (live + recent)")
    public ResponseEntity<?> sessions(@PathVariable String id) {
        var u = admin.get(id);
        if (u == null) return ResponseEntity.status(404).body(new ErrorResponse("user not found"));
        return ResponseEntity.ok(admin.sessionsForUser(id));
    }

    @PostMapping("/{id}/sessions/revoke")
    @Operation(summary = "Revoke all auth sessions for a user (force-logout everywhere)")
    public ResponseEntity<?> revoke(HttpServletRequest req, @PathVariable String id) {
        var current = currentUser(req);
        int n = admin.revokeAllSessions(current.user().id(), clientIp(req), sessionId(req), id);
        if (n < 0) return ResponseEntity.status(404).body(new ErrorResponse("user not found"));
        return ResponseEntity.ok(Map.of("revoked", n));
    }

    @PostMapping("/{id}/password")
    @Operation(summary = "Admin password reset. Revokes all existing sessions.")
    public ResponseEntity<?> resetPassword(HttpServletRequest req, @PathVariable String id, @RequestBody PasswordResetDto body) {
        if (body == null || body.newPassword() == null || body.newPassword().length() < 8) {
            return ResponseEntity.badRequest().body(new ErrorResponse("newPassword must be at least 8 characters"));
        }
        var current = currentUser(req);
        boolean ok = admin.resetPassword(current.user().id(), clientIp(req), sessionId(req), id, body.newPassword());
        if (!ok) return ResponseEntity.status(404).body(new ErrorResponse("user not found"));
        return ResponseEntity.noContent().build();
    }

    private AuthService.CurrentUser currentUser(HttpServletRequest req) {
        return (AuthService.CurrentUser) req.getAttribute(SessionAuthFilter.CURRENT_USER_ATTR);
    }

    private static String clientIp(HttpServletRequest req) {
        var fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    private static String sessionId(HttpServletRequest req) {
        var s = req.getHeader(SessionAuthFilter.SESSION_HEADER);
        return s == null || s.isBlank() ? null : s;
    }

    @Schema(name = "AdminCreateUserInput",
        description = "Create-user payload. All fields required except firstname/lastname/email/isAdmin.",
        example = "{\"username\":\"alice\",\"password\":\"correct horse battery staple\",\"firstname\":\"Alice\",\"lastname\":\"Liddell\",\"email\":\"alice@example.com\",\"isAdmin\":false}")
    public record CreateUserDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String username,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String password,
        String firstname, String lastname, String email,
        Boolean isAdmin
    ) {}

    @Schema(name = "AdminUpdateUserInput",
        description = "Update-user payload. Omitted fields are unchanged. isAdmin is tri-state: omit to leave role, set to true/false to change.")
    public record UpdateUserDto(
        String username, String firstname, String lastname, String email,
        Boolean isAdmin
    ) {}

    @Schema(name = "AdminPasswordResetInput",
        description = "Password reset payload. New password must be at least 8 characters.",
        example = "{\"newPassword\":\"correct horse battery staple\"}")
    public record PasswordResetDto(String newPassword) {}

    @Schema(name = "AdminUserList",
        description = "Paginated user list.",
        example = "{\"items\":[{\"id\":\"u1\",\"username\":\"alice\",\"isAdmin\":true,\"createdAt\":\"2026-06-17T22:00:00Z\"}],\"total\":1,\"page\":0,\"rows\":20,\"pages\":1}")
    public record UserListDto(List<UserResponse> items, long total, int page, int rows, int pages) {}
}
