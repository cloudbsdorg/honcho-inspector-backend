package com.revytechinc.honchoinspector.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Convert;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Server-side login session. The id is a 24-byte random hex string
 * sent back to the browser as X-Session-Id. expires_at is null for
 * never-expiring sessions (the default when SESSION_TTL_MINUTES=0).
 */
@Entity
@Table(name = "auth_sessions")
public class AuthSessionEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "created_at", nullable = false, length = 32)
    private String createdAt;

    @Column(name = "last_seen_at", nullable = false, length = 32)
    private String lastSeenAt;

    @Column(name = "expires_at", length = 32)
    private String expiresAt;

    public AuthSessionEntity() {}

    public AuthSessionEntity(
        String id, String userId, Instant createdAt, Instant lastSeenAt, Instant expiresAt
    ) {
        this.id = id;
        this.userId = userId;
        this.createdAt = createdAt.toString();
        this.lastSeenAt = lastSeenAt.toString();
        this.expiresAt = expiresAt == null ? null : expiresAt.toString();
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getCreatedAt() { return createdAt; }
    public String getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(java.time.Instant lastSeenAt) { this.lastSeenAt = lastSeenAt.toString(); }
    public String getExpiresAt() { return expiresAt; }
    public Instant getCreatedAtAsInstant() { return Instant.parse(createdAt); }
    public Instant getLastSeenAtAsInstant() { return Instant.parse(lastSeenAt); }
    public Instant getExpiresAtAsInstant() { return expiresAt == null ? null : Instant.parse(expiresAt); }
}
