package com.revytechinc.honchoinspector.auth;

import java.time.Instant;

public record User(
    String id,
    String username,
    String passwordHash,
    String firstname,
    String lastname,
    String email,
    boolean isAdmin,
    Instant createdAt
) {
    public User {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (username == null || username.isBlank()) throw new IllegalArgumentException("username required");
        if (passwordHash == null || passwordHash.isBlank()) throw new IllegalArgumentException("passwordHash required");
        if (createdAt == null) throw new IllegalArgumentException("createdAt required");
    }
}
