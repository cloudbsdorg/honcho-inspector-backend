package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.config.OpenApiConfig;
import com.revytechinc.honchoinspector.model.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * First-run configuration endpoints. Available ONLY when the database
 * is empty (no users). Once any user exists, every endpoint here
 * returns 409 Conflict. The UI uses these to bootstrap the very first
 * admin from a freshly-installed backend without requiring any
 * pre-existing credentials.
 *
 * For the config-file path (server-side bootstrap), the operator can
 * instead populate `honcho.bootstrap.*` in
 * `/etc/honcho-inspector/application.yml` and let
 * {@link AdminBootstrap} create the first admin on startup. The two
 * paths are mutually exclusive: the first one to create a user wins.
 */
@RestController
@RequestMapping("/api/setup")
@Tag(name = OpenApiConfig.TAG_SETUP,
    description = "First-run configuration. Every endpoint here is reachable only when the database is empty (no users); once any user exists they all return 409. After the first admin is created via /api/setup/first-admin, all subsequent user and config management flows through the /api/admin/* surface.")
public class SetupController {

    private final AuthService auth;
    private final UserDao users;
    private final AuthSessionDao sessions;

    public SetupController(AuthService auth, UserDao users, AuthSessionDao sessions) {
        this.auth = auth;
        this.users = users;
        this.sessions = sessions;
    }

    @Schema(
        name = "FirstAdminInput",
        description = "Initial admin payload accepted by /api/setup/first-admin. The new user is always isAdmin=true (this endpoint is reachable only when the database is empty).",
        example = "{\"username\":\"alice\",\"password\":\"correct horse battery staple\",\"firstname\":\"Alice\",\"lastname\":\"Admin\",\"email\":\"alice@example.com\"}"
    )
    public record FirstAdminDto(
        @Schema(description = "Unique username. Required.", example = "alice", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String username,

        @Schema(description = "Plaintext password. Minimum 8 characters. Hashed with bcrypt cost 12 server-side; never logged.", example = "correct horse battery staple", minLength = 8, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 8) String password,

        @Schema(description = "Optional first name.", example = "Alice")
        String firstname,

        @Schema(description = "Optional last name.", example = "Admin")
        String lastname,

        @Schema(description = "Optional email (must be unique across the users table if set).", example = "alice@example.com")
        String email
    ) {}

    @PostMapping("/first-admin")
    @Operation(
        summary = "Create the very first admin (first-run only)",
        description = "Reachable only when the database is empty. Creates an admin user and immediately opens a session for them, returning the same shape as POST /api/auth/login. The new user is always isAdmin=true; the `isAdmin` flag is server-determined, not client-supplied. After this call, /api/setup/* is locked and all further user management goes through /api/admin/users."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "First admin created; session returned",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation error (e.g. password too short)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Database is not empty; first admin already exists. Use /api/auth/register (admin-only) to add more users.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> firstAdmin(@Valid @RequestBody FirstAdminDto body) {
        if (users.count() > 0) {
            return ResponseEntity.status(409).body(new ErrorResponse(
                "first admin already exists; use POST /api/auth/register (admin-only) to add more users"
            ));
        }
        try {
            var user = auth.adminCreate(
                body.username(),
                body.password(),
                body.firstname(),
                body.lastname(),
                body.email(),
                true
            );
            var session = new AuthSession(
                UUID.randomUUID().toString().replace("-", ""),
                user.id(),
                Instant.now(),
                Instant.now(),
                Optional.empty()
            );
            sessions.insert(session);
            return ResponseEntity.ok(LoginResponse.of(session, user));
        } catch (AuthService.UserExistsException e) {
            return ResponseEntity.status(409).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }
}
