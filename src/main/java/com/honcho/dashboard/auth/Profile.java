package com.honcho.dashboard.auth;

import java.time.Instant;

public record Profile(
    String id,
    String userId,
    String label,
    String apiKeyEncrypted,
    String baseUrl,
    String workspaceId,
    String honchoUserName,
    Instant createdAt,
    Instant updatedAt
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
    }
}
