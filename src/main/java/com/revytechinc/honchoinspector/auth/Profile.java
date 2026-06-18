package com.revytechinc.honchoinspector.auth;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(
    name = "Profile",
    description = "A Honcho profile owned by a user. The API key is stored encrypted at rest; the public API returns only the opaque `apiKeyEncrypted` blob — the plaintext key is exposed exclusively through `GET /api/profiles/{id}/reveal`.",
    example = "{\"id\":\"p1q2r3s4t5u6v7w8\",\"userId\":\"a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4\",\"label\":\"Production\",\"apiKeyEncrypted\":\"enc:v1:YWFhYWFhYWFhYWFhYWFhYQ==:MTIzNDU2Nzg5\",\"baseUrl\":\"https://api.honcho.dev\",\"workspaceId\":\"ws_abc123\",\"honchoUserName\":\"alice\",\"createdAt\":\"2026-06-17T22:00:00Z\",\"updatedAt\":\"2026-06-17T22:00:00Z\"}"
)
public record Profile(
    @Schema(description = "Opaque hex profile id", example = "p1q2r3s4t5u6v7w8")
    String id,

    @Schema(description = "Opaque hex id of the owning user", example = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
    String userId,

    @Schema(description = "Human-readable label shown in the UI", example = "Production")
    String label,

    @Schema(description = "Encrypted (AES-256-GCM) Honcho API key blob. Use `GET /api/profiles/{id}/reveal` to obtain the plaintext.", example = "enc:v1:YWFhYWFhYWFhYWFhYWFhYQ==:MTIzNDU2Nzg5")
    String apiKeyEncrypted,

    @Schema(description = "Honcho base URL, with no trailing `/mcp` (the proxy strips it if present)", example = "https://api.honcho.dev")
    String baseUrl,

    @Schema(description = "Honcho workspace id this profile is scoped to", example = "ws_abc123")
    String workspaceId,

    @Schema(description = "Honcho-side user name sent in the `X-Honcho-User-Name` header on every proxied request", example = "alice")
    String honchoUserName,

    @Schema(description = "ISO-8601 instant the profile was created", example = "2026-06-17T22:00:00Z")
    Instant createdAt,

    @Schema(description = "ISO-8601 instant the profile was last updated", example = "2026-06-17T22:00:00Z")
    Instant updatedAt,

    @Schema(description = "Honcho API surface version this profile targets (e.g. `v2`, `v3`). Nullable: profiles created before T16 lack this value and the controller falls back to the server default. Honcho v3 is the product default.", example = "v3", nullable = true)
    String apiVersion
) {
    public Profile {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId required");
        if (label == null || label.isBlank()) throw new IllegalArgumentException("label required");
        if (apiKeyEncrypted == null || apiKeyEncrypted.isBlank()) throw new IllegalArgumentException("apiKeyEncrypted required");
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl required");
        if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspaceId required");
        if (honchoUserName == null || honchoUserName.isBlank()) throw new IllegalArgumentException("honchoUserName required");
        if (createdAt == null) throw new IllegalArgumentException("createdAt required");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt required");
        // apiVersion is intentionally not validated: nullable + blankable so
        // pre-T16 rows (column added by SchemaMigrator, never set by user)
        // load cleanly. HonchoClientFactory.resolveVersion handles blank.
    }
}
