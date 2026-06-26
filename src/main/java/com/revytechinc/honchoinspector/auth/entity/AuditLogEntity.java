package com.revytechinc.honchoinspector.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Admin audit log entry. `metadata` is JSON-encoded as TEXT (not
 * a real JSON column type — SQLite has no native JSON type, so we
 * keep the schema's TEXT column and rely on Jackson to (de)serialize
 * at the application boundary).
 */
@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "actor_user_id", length = 64)
    private String actorUserId;

    @Column(name = "action", nullable = false, length = 128)
    private String action;

    @Column(name = "target_user_id", length = 64)
    private String targetUserId;

    @Column(name = "target_resource", length = 256)
    private String targetResource;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "metadata", length = 4096)
    private String metadata;

    @Column(name = "created_at", nullable = false, length = 32)
    private String createdAt;

    public AuditLogEntity() {}

    public AuditLogEntity(
        String id, String actorUserId, String action, String targetUserId,
        String targetResource, String ip, String sessionId, String metadata,
        Instant createdAt
    ) {
        this.id = id;
        this.actorUserId = actorUserId;
        this.action = action;
        this.targetUserId = targetUserId;
        this.targetResource = targetResource;
        this.ip = ip;
        this.sessionId = sessionId;
        this.metadata = metadata;
        this.createdAt = createdAt.toString();
    }

    public String getId() { return id; }
    public String getActorUserId() { return actorUserId; }
    public String getAction() { return action; }
    public String getTargetUserId() { return targetUserId; }
    public String getTargetResource() { return targetResource; }
    public String getIp() { return ip; }
    public String getSessionId() { return sessionId; }
    public String getMetadata() { return metadata; }
    public String getCreatedAt() { return createdAt; }
    public Instant getCreatedAtAsInstant() { return Instant.parse(createdAt); }
}
