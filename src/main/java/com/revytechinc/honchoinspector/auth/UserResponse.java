package com.revytechinc.honchoinspector.auth;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(
    name = "UserResponse",
    description = "Public view of a registered user. The password hash is never returned. `isAdmin` is true for the first registered user.",
    example = "{\"id\":\"a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4\",\"username\":\"alice\",\"isAdmin\":true,\"createdAt\":\"2026-06-17T22:00:00Z\"}"
)
public record UserResponse(
    @Schema(description = "Opaque hex user id", example = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
    String id,

    @Schema(description = "Unique username (case-sensitive on the wire)", example = "alice")
    String username,

    @Schema(description = "True for the first user registered after a fresh DB; there is no admin promotion endpoint in this version", example = "true")
    boolean isAdmin,

    @Schema(description = "ISO-8601 instant the user was created", example = "2026-06-17T22:00:00Z")
    Instant createdAt
) {
    public static UserResponse from(User u) {
        return new UserResponse(u.id(), u.username(), u.isAdmin(), u.createdAt());
    }
}
