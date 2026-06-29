package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.auth.repo.AuthSessionRepository;
import com.revytechinc.honchoinspector.auth.repo.ProfileRepository;
import com.revytechinc.honchoinspector.auth.repo.UserRepository;

import com.revytechinc.honchoinspector.config.OpenApiConfig;
import com.revytechinc.honchoinspector.filter.SessionAuthFilter;
import com.revytechinc.honchoinspector.model.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = OpenApiConfig.TAG_AUTH,
    description = "User registration, login, logout, and session lookup. The auth endpoints themselves are public (no `X-Session-Id` required); `/api/auth/me` and `/api/auth/logout` require it.")
public class AuthController {

    private final AuthService auth;
    private final UserRepository users;
    private final AuthSessionRepository sessions;
    private final ProfileRepository profiles;
    private final AdminAudit audit;
    /**
     * Mirrors {@code honcho.ui.chat-enabled} so the frontend
     * knows whether to show the chat button + popout. Default
     * false — operators opt in by setting
     * {@code HONCHO_UI_CHAT_ENABLED=true}.
     */
    private final boolean chatEnabled;

    public AuthController(
        AuthService auth,
        UserRepository users,
        AuthSessionRepository sessions,
        ProfileRepository profiles,
        AdminAudit audit,
        @org.springframework.beans.factory.annotation.Value("${honcho.ui.chat-enabled:false}") boolean chatEnabled
    ) {
        this.auth = auth;
        this.users = users;
        this.sessions = sessions;
        this.profiles = profiles;
        this.audit = audit;
        this.chatEnabled = chatEnabled;
    }

    /**
     * Best-effort client IP for audit logging. Honors
     * X-Forwarded-For (first hop) for reverse-proxy deployments,
     * else falls back to the socket peer. Same logic the admin
     * endpoints use; duplicated here so the auth endpoints don't
     * take a dependency on the admin package.
     */
    private static String clientIp(HttpServletRequest req) {
        var fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    @Schema(
        name = "CredentialsInput",
        description = "Username and password payload shared by `/api/auth/register` and `/api/auth/login`.",
        example = "{\"username\":\"alice\",\"password\":\"correct horse battery staple\"}"
    )
    public record CredentialsDto(
        @Schema(description = "Unique username (case-sensitive on the wire). Required.", example = "alice", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String username,

        @Schema(description = "Plaintext password. Minimum 8 characters. Hashed with bcrypt cost 12 server-side; never logged.", example = "correct horse battery staple", minLength = 8, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 8) String password
    ) {}

    @PostMapping("/auth/register")
    @RequireAdmin
    @Operation(
        summary = "Register a new user (admin-only)",
        description = "Creates a new user account. Admin-only. The first admin is created on a fresh database by the AdminBootstrap component from honcho.bootstrap.admin-username / admin-password in /etc/honcho-inspector/application.yml, NOT via this endpoint. Returns 409 if the username is taken and 400 for validation errors."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "User created",
            content = @Content(schema = @Schema(implementation = UserResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation error (e.g. password too short)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "Caller is not admin",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Username already exists",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> register(@Valid @RequestBody CredentialsDto body) {
        try {
            var user = auth.register(body.username(), body.password());
            return ResponseEntity.status(201).body(UserResponse.from(user));
        } catch (AuthService.UserExistsException e) {
            return ResponseEntity.status(409).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/auth/login")
    @Operation(
        summary = "Log in and obtain a session id",
        description = "Validates username + password and, on success, returns a fresh session id. Send it back on every subsequent request as the `X-Session-Id` header. Returns 401 on invalid credentials without revealing whether the username exists."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Authenticated; session id returned",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(responseCode = "401", description = "Invalid username or password",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> login(@Valid @RequestBody CredentialsDto body) {
        try {
            var result = auth.login(body.username(), body.password());
            return ResponseEntity.ok(LoginResponse.of(result.session(), result.user()));
        } catch (AuthService.InvalidCredentialsException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/auth/logout")
    @Operation(
        summary = "Invalidate the current session",
        description = "Reads the `X-Session-Id` header, deletes the session server-side, and returns 200 with `{ok: true}`. Idempotent: calling with a missing/unknown session id is a no-op success."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Session invalidated (or already absent)",
            content = @Content(schema = @Schema(example = "{\"ok\":true}")))
    })
    public ResponseEntity<?> logout(HttpServletRequest request) {
        var sessionId = request.getHeader(SessionAuthFilter.SESSION_HEADER);
        auth.logout(sessionId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/auth/me")
    @Operation(
        summary = "Get the currently authenticated user",
        description = "Returns the user record for the session identified by the `X-Session-Id` header. Useful on app reload to re-hydrate the UI."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Current user",
            content = @Content(schema = @Schema(implementation = UserResponse.class))),
        @ApiResponse(responseCode = "401", description = "No valid session",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> me(HttpServletRequest request) {
        var current = (AuthService.CurrentUser) request.getAttribute(SessionAuthFilter.CURRENT_USER_ATTR);
        if (current == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("not authenticated"));
        }
        return ResponseEntity.ok(UserResponse.from(current.user()));
    }

    @Schema(
        name = "ChangeOwnPasswordInput",
        description = "Self-service password change payload. The currentPassword is verified against the stored hash (so a stolen session cookie alone cannot lock the real user out). The newPassword must be at least 8 characters. On success, ALL of the user's existing sessions are revoked — including the one making the call — and the caller has to log in again with the new password.",
        example = "{\"currentPassword\":\"correct horse battery staple\",\"newPassword\":\"new-pass-phrase-here\"}"
    )
    public record ChangeOwnPasswordDto(
        @Schema(description = "Caller's current password (verified server-side).", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String currentPassword,

        @Schema(description = "New password. Minimum 8 characters.", example = "new-pass-phrase-here", minLength = 8, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 8) String newPassword
    ) {}

    @PostMapping("/auth/me/password")
    @Operation(
        summary = "Change the currently authenticated user's own password",
        description = "Self-service password change. Requires the current password (defense against a stolen session cookie). On success: re-hashes the new password, records a `user.password.change` audit event, and revokes ALL of the user's existing sessions (including the one making this call) so the new password takes effect across all clients. The caller is redirected to the login screen on the next API call because their current session is gone.",
        operationId = "authChangeOwnPassword"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Password changed; all sessions revoked"),
        @ApiResponse(responseCode = "400", description = "Validation error (new password too short, etc.)"),
        @ApiResponse(responseCode = "401", description = "Not authenticated, or current password is wrong",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> changeOwnPassword(
        HttpServletRequest request,
        @Valid @RequestBody ChangeOwnPasswordDto body
    ) {
        var current = (AuthService.CurrentUser) request.getAttribute(SessionAuthFilter.CURRENT_USER_ATTR);
        if (current == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("not authenticated"));
        }
        try {
            auth.changeOwnPassword(current.user().id(), body.currentPassword(), body.newPassword());
        } catch (AuthService.InvalidCredentialsException e) {
            // Same generic message as the login endpoint — don't
            // leak whether the username exists or whether the
            // current password is wrong.
            return ResponseEntity.status(401).body(new ErrorResponse("invalid username or password"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
        // Record the self-service change. AdminUserService does
        // the same for the admin-reset path; keeping both audits
        // symmetric means the admin audit tab shows self-service
        // and admin-driven changes in the same shape.
        audit.record(
            current.user().id(), "user.password.change", current.user().id(), null,
            clientIp(request), request.getHeader(SessionAuthFilter.SESSION_HEADER),
            java.util.Map.of());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/health")
    @Operation(
        summary = "Liveness / readiness probe",
        description = "Public unauthenticated health endpoint. Returns the service status, a `first_run` boolean (and `needs_register` alias), and a `chat_enabled` boolean. The UI uses `first_run` to decide whether to render the bootstrap wizard on first launch. The UI uses `chat_enabled` to hide the chat button + popout when the server has chat disabled (the operator's `HONCHO_UI_CHAT_ENABLED` env var is false, which is the default). Once any user exists, both `firstRun` flags are false. The previous version leaked user/session/profile counts to unauthenticated callers; those have been moved to the admin-only dashboard overview."
    )
    @ApiResponse(responseCode = "200", description = "Service is up",
        content = @Content(schema = @Schema(example = "{\"ok\":true,\"firstRun\":false,\"needsRegister\":false,\"chatEnabled\":false}")))
    public ResponseEntity<?> health() {
        boolean firstRun = auth.isFirstUser();
        return ResponseEntity.ok(Map.of(
            "ok", true,
            "firstRun", firstRun,
            "needsRegister", firstRun,
            "chatEnabled", chatEnabled
        ));
    }
}
