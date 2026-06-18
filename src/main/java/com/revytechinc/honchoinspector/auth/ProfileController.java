package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.config.OpenApiConfig;
import com.revytechinc.honchoinspector.filter.SessionAuthFilter;
import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.model.ErrorResponse;
import com.revytechinc.honchoinspector.model.HonchoContext;
import com.revytechinc.honchoinspector.service.HonchoProxyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/profiles")
@Tag(name = OpenApiConfig.TAG_PROFILES,
    description = "CRUD for Honcho profiles owned by the current user. Each profile stores an encrypted API key, base URL, workspace id, and Honcho user name. All endpoints require the `X-Session-Id` header; cross-user access is rejected with 404.")
public class ProfileController {

    private final ProfileService profiles;
    private final HonchoProxyService honcho;

    public ProfileController(ProfileService profiles, HonchoProxyService honcho) {
        this.profiles = profiles;
        this.honcho = honcho;
    }

    @Schema(
        name = "ProfileCreateInput",
        description = "Body for `POST /api/profiles`. The API key is encrypted with AES-256-GCM server-side immediately upon receipt; only the encrypted blob is ever persisted.",
        example = "{\"label\":\"Production\",\"apiKey\":\"hmc_5f4dcc3b5aa765d61d8327deb882cf99\",\"baseUrl\":\"https://api.honcho.dev\",\"workspaceId\":\"ws_abc123\",\"honchoUserName\":\"alice\",\"apiVersion\":\"v3\"}"
    )
    public record ProfileCreateDto(
        @Schema(description = "Human-readable label shown in the UI", example = "Production", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String label,

        @Schema(description = "Plaintext Honcho API key. Encrypted at rest with AES-256-GCM; never returned in subsequent list/get responses.", example = "hmc_5f4dcc3b5aa765d61d8327deb882cf99", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String apiKey,

        @Schema(description = "Honcho base URL, with no trailing slash. A trailing `/mcp` is stripped automatically by the proxy.", example = "https://api.honcho.dev", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String baseUrl,

        @Schema(description = "Honcho workspace id this profile is scoped to", example = "ws_abc123", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String workspaceId,

        @Schema(description = "Honcho-side user name sent in the `X-Honcho-User-Name` header on every proxied request", example = "alice", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String honchoUserName,

        @Schema(description = "Honcho API version this profile targets (e.g. `v2`, `v3`). Optional; null/blank falls back to the server default (`honcho.api-version`, `v3`).", example = "v3", nullable = true)
        String apiVersion
    ) {}

    @Schema(
        name = "ProfileUpdateInput",
        description = "Body for `PUT /api/profiles/{id}`. Every field is optional; only the fields present in the JSON are written. Re-encrypting the API key is allowed (pass a new plaintext value).",
        example = "{\"label\":\"Production (US-East)\",\"apiKey\":\"hmc_NEWKEY\",\"apiVersion\":\"v3\"}"
    )
    public record ProfileUpdateDto(
        @Schema(description = "New label, or null to leave unchanged", example = "Production (US-East)")
        String label,

        @Schema(description = "New plaintext API key, or null to leave unchanged. Re-encrypted on write.", example = "hmc_NEWKEY")
        String apiKey,

        @Schema(description = "New base URL, or null to leave unchanged", example = "https://api.honcho.dev")
        String baseUrl,

        @Schema(description = "New workspace id, or null to leave unchanged", example = "ws_abc123")
        String workspaceId,

        @Schema(description = "New Honcho user name, or null to leave unchanged", example = "alice")
        String honchoUserName,

        @Schema(description = "New Honcho API version, or null to leave unchanged.", example = "v3", nullable = true)
        String apiVersion
    ) {}

    @Schema(
        name = "ProfileWithKey",
        description = "Returned only by `GET /api/profiles/{id}/reveal`. Carries the plaintext API key — use sparingly and only when the UI needs to display it.",
        example = "{\"profile\":{\"id\":\"p1q2r3s4t5u6v7w8\",\"userId\":\"a1b2c3d4\",\"label\":\"Production\",\"apiKeyEncrypted\":\"enc:v1:...\",\"baseUrl\":\"https://api.honcho.dev\",\"workspaceId\":\"ws_abc123\",\"honchoUserName\":\"alice\",\"createdAt\":\"2026-06-17T22:00:00Z\",\"updatedAt\":\"2026-06-17T22:00:00Z\"},\"apiKey\":\"hmc_5f4dcc3b5aa765d61d8327deb882cf99\"}"
    )
    public record ProfileWithKeyDto(Profile profile, String apiKey) {}

    @GetMapping
    @Operation(
        summary = "List the current user's profiles",
        description = "Returns all profiles owned by the user identified by the `X-Session-Id` header. The encrypted API key blob is included for each; use `/reveal` to obtain the plaintext."
    )
    @ApiResponse(responseCode = "200", description = "Profiles (may be empty)",
        content = @Content(array = @ArraySchema(schema = @Schema(implementation = Profile.class))))
    public ResponseEntity<List<Profile>> list(HttpServletRequest req) {
        var current = currentUser(req);
        return ResponseEntity.ok(profiles.list(current.user().id()));
    }

    @PostMapping
    @Operation(
        summary = "Create a new profile",
        description = "Creates a profile owned by the current user. The plaintext API key in the body is encrypted immediately and never returned in this response — call `/reveal` if you need it back."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Profile created",
            content = @Content(schema = @Schema(implementation = Profile.class))),
        @ApiResponse(responseCode = "400", description = "Validation error",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> create(HttpServletRequest req, @Valid @RequestBody ProfileCreateDto body) {
        var current = currentUser(req);
        var p = profiles.create(
            current.user().id(),
            body.label(), body.apiKey(), body.baseUrl(),
            body.workspaceId(), body.honchoUserName(),
            body.apiVersion()
        );
        return ResponseEntity.status(201).body(p);
    }

    @GetMapping("/{id}")
    @Operation(
        summary = "Fetch a single profile by id",
        description = "Returns the profile record (encrypted API key blob included). 404 if the id does not exist OR belongs to a different user — the response is identical in both cases to avoid leaking ownership."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Profile found",
            content = @Content(schema = @Schema(implementation = Profile.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found or not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> get(
        HttpServletRequest req,
        @Parameter(description = "Hex profile id", example = "p1q2r3s4t5u6v7w8")
        @PathVariable String id
    ) {
        var current = currentUser(req);
        return profiles.get(current.user().id(), id)
            .<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.status(404).body(new ErrorResponse("profile not found")));
    }

    @GetMapping("/{id}/reveal")
    @Operation(
        summary = "Decrypt and return the profile's API key",
        description = "Decrypts the stored API key blob and returns it alongside the profile record. **Use sparingly** — the plaintext key should only be exposed when the UI needs to display or copy it."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Plaintext key returned",
            content = @Content(schema = @Schema(implementation = ProfileWithKeyDto.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found or not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> reveal(
        HttpServletRequest req,
        @Parameter(description = "Hex profile id", example = "p1q2r3s4t5u6v7w8")
        @PathVariable String id
    ) {
        var current = currentUser(req);
        return profiles.getWithKey(current.user().id(), id)
            .map(pwk -> ResponseEntity.ok((Object) new ProfileWithKeyDto(pwk.profile(), pwk.apiKey())))
            .orElseGet(() -> ResponseEntity.status(404).body(new ErrorResponse("profile not found")));
    }

    @PutMapping("/{id}")
    @Operation(
        summary = "Update a profile (partial)",
        description = "Updates only the fields present in the body. Passing a new `apiKey` re-encrypts and overwrites the stored blob; omitting it leaves the existing key intact."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Profile updated",
            content = @Content(schema = @Schema(implementation = Profile.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found or not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> update(
        HttpServletRequest req,
        @Parameter(description = "Hex profile id", example = "p1q2r3s4t5u6v7w8")
        @PathVariable String id,
        @RequestBody ProfileUpdateDto body
    ) {
        var current = currentUser(req);
        return profiles.update(
            current.user().id(), id,
            body.label(), body.apiKey(), body.baseUrl(),
            body.workspaceId(), body.honchoUserName(),
            body.apiVersion()
        ).<ResponseEntity<?>>map(ResponseEntity::ok)
         .orElseGet(() -> ResponseEntity.status(404).body(new ErrorResponse("profile not found")));
    }

    @DeleteMapping("/{id}")
    @Operation(
        summary = "Delete a profile",
        description = "Removes the profile. Returns 204 on success and 404 if the id does not exist or is not owned by the current user."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Profile deleted"),
        @ApiResponse(responseCode = "404", description = "Profile not found or not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> delete(
        HttpServletRequest req,
        @Parameter(description = "Hex profile id", example = "p1q2r3s4t5u6v7w8")
        @PathVariable String id
    ) {
        var current = currentUser(req);
        var ok = profiles.delete(current.user().id(), id);
        return ok
            ? ResponseEntity.noContent().build()
            : ResponseEntity.status(404).body(new ErrorResponse("profile not found"));
    }

    @PostMapping("/{id}/test")
    @Operation(
        summary = "Test that the profile can reach Honcho",
        description = "Decrypts the API key, builds an `Authorization: Bearer …` header, and issues a `GET /v3/workspaces/{workspaceId}` against the profile's `baseUrl`. Returns `{ok: true, message: \"reachable\"}` on success or `{ok: false, error: …}` on any HTTP or transport failure."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Result of the connectivity check",
            content = @Content(schema = @Schema(example = "{\"ok\":true,\"message\":\"reachable\"}"))),
        @ApiResponse(responseCode = "404", description = "Profile not found or not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> test(
        HttpServletRequest req,
        @Parameter(description = "Hex profile id", example = "p1q2r3s4t5u6v7w8")
        @PathVariable String id
    ) {
        var current = currentUser(req);
        var pwk = profiles.getWithKey(current.user().id(), id).orElse(null);
        if (pwk == null) {
            return ResponseEntity.status(404).body(new ErrorResponse("profile not found"));
        }
        var ctx = new HonchoContext(pwk.apiKey(), pwk.profile().baseUrl(),
                                    pwk.profile().workspaceId(), pwk.profile().honchoUserName());
        try {
            honcho.testConnection(ctx);
            return ResponseEntity.ok(Map.of("ok", true, "message", "reachable"));
        } catch (HonchoCallException e) {
            return ResponseEntity.status(e.status() >= 500 ? 502 : e.status())
                .body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    private AuthService.CurrentUser currentUser(HttpServletRequest req) {
        return (AuthService.CurrentUser) req.getAttribute(SessionAuthFilter.CURRENT_USER_ATTR);
    }
}
