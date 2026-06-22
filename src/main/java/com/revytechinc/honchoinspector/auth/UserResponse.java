package com.revytechinc.honchoinspector.auth;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(
    name = "UserResponse",
    description = "Public view of a registered user. The password hash is never returned. `isAdmin` is true for the first registered user.",
    example = "{\"id\":\"a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4\",\"username\":\"alice\",\"firstname\":\"Alice\",\"lastname\":\"Liddell\",\"email\":\"alice@example.com\",\"isAdmin\":true,\"createdAt\":\"2026-06-17T22:00:00Z\"}"
)
public record UserResponse(
    @Schema(description = "Opaque hex user id", example = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
    String id,

    @Schema(description = "Unique username (case-sensitive on the wire)", example = "alice")
    String username,

    @Schema(description = "First name (admin-supplied at onboarding)", example = "Alice", nullable = true)
    String firstname,

    @Schema(description = "Last name (admin-supplied at onboarding)", example = "Liddell", nullable = true)
    String lastname,

    @Schema(description = "Email address (admin-supplied at onboarding; unique when set)", example = "alice@example.com", nullable = true)
    String email,

    @Schema(description = "True if this user has admin privileges", example = "true")
    boolean isAdmin,

    @Schema(description = "ISO-8601 instant the user was created", example = "2026-06-17T22:00:00Z")
    Instant createdAt
) {
    public static UserResponse from(User u) {
        return new UserResponse(u.id(), u.username(), u.firstname(), u.lastname(), u.email(), u.isAdmin(), u.createdAt());
    }
}
