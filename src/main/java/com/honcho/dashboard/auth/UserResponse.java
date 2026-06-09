package com.honcho.dashboard.auth;

import java.time.Instant;

public record UserResponse(
    String id,
    String username,
    boolean isAdmin,
    Instant createdAt
) {
    public static UserResponse from(User u) {
        return new UserResponse(u.id(), u.username(), u.isAdmin(), u.createdAt());
    }
}
