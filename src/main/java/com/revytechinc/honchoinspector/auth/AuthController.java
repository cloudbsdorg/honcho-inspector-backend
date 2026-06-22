package com.revytechinc.honchoinspector.auth;

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
    private final UserDao users;
    private final AuthSessionDao sessions;
    private final ProfileDao profiles;

    public AuthController(AuthService auth, UserDao users, AuthSessionDao sessions, ProfileDao profiles) {
        this.auth = auth;
        this.users = users;
        this.sessions = sessions;
        this.profiles = profiles;
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

    @GetMapping("/health")
    @Operation(
        summary = "Liveness / readiness probe",
        description = "Public unauthenticated health endpoint. Returns only the service status, a `first_run` boolean, and a `needs_register` alias. The UI uses `first_run` to decide whether to render the bootstrap wizard on first launch. Once any user exists, both flags are false. The previous version leaked user/session/profile counts to unauthenticated callers; those have been moved to the admin-only dashboard overview."
    )
    @ApiResponse(responseCode = "200", description = "Service is up",
        content = @Content(schema = @Schema(example = "{\"ok\":true,\"first_run\":false,\"needs_register\":false}")))
    public ResponseEntity<?> health() {
        boolean firstRun = auth.isFirstUser();
        return ResponseEntity.ok(Map.of(
            "ok", true,
            "first_run", firstRun,
            "needs_register", firstRun
        ));
    }
}
