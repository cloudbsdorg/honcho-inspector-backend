package com.honcho.dashboard.auth;

import java.time.Instant;
import java.util.Optional;

public record AuthSession(
    String id,
    String userId,
    Instant createdAt,
    Instant lastSeenAt,
    Optional<Instant> expiresAt
) {
    public AuthSession {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId required");
        if (createdAt == null) throw new IllegalArgumentException("createdAt required");
        if (lastSeenAt == null) throw new IllegalArgumentException("lastSeenAt required");
    }

    public boolean isExpired(Instant now) {
        return expiresAt.map(now::isAfter).orElse(false);
    }
}
