package com.revytechinc.honchoinspector.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Convert;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Per-user Honcho connection profile. Holds the AES-encrypted API
 * key (never plaintext) plus the workspace + Honcho user-name
 * triple used to talk to the upstream.
 */
@Entity
@Table(name = "honcho_profiles")
public class ProfileEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "label", nullable = false, length = 128)
    private String label;

    @Column(name = "api_key_encrypted", nullable = false, length = 1024)
    private String apiKeyEncrypted;

    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    @Column(name = "workspace_id", nullable = false, length = 128)
    private String workspaceId;

    @Column(name = "honcho_user_name", nullable = false, length = 128)
    private String honchoUserName;

    @Column(name = "api_version", length = 32)
    private String apiVersion;

    @Column(name = "created_at", nullable = false, length = 32)
    private String createdAt;

    @Column(name = "updated_at", nullable = false, length = 32)
    private String updatedAt;

    public ProfileEntity() {}

    public ProfileEntity(
        String id, String userId, String label, String apiKeyEncrypted,
        String baseUrl, String workspaceId, String honchoUserName,
        String apiVersion, Instant createdAt, Instant updatedAt
    ) {
        this.id = id;
        this.userId = userId;
        this.label = label;
        this.apiKeyEncrypted = apiKeyEncrypted;
        this.baseUrl = baseUrl;
        this.workspaceId = workspaceId;
        this.honchoUserName = honchoUserName;
        this.apiVersion = apiVersion;
        this.createdAt = createdAt.toString();
        this.updatedAt = updatedAt.toString();
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getLabel() { return label; }
    public String getApiKeyEncrypted() { return apiKeyEncrypted; }
    public String getBaseUrl() { return baseUrl; }
    public String getWorkspaceId() { return workspaceId; }
    public String getHonchoUserName() { return honchoUserName; }
    public String getApiVersion() { return apiVersion; }
    public String getCreatedAt() { return createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public Instant getCreatedAtAsInstant() { return Instant.parse(createdAt); }
    public Instant getUpdatedAtAsInstant() { return Instant.parse(updatedAt); }

    public void setId(String v) { this.id = v; }
    public void setUserId(String v) { this.userId = v; }
    public void setLabel(String v) { this.label = v; }
    public void setApiKeyEncrypted(String v) { this.apiKeyEncrypted = v; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public void setWorkspaceId(String v) { this.workspaceId = v; }
    public void setHonchoUserName(String v) { this.honchoUserName = v; }
    public void setApiVersion(String v) { this.apiVersion = v; }
    public void setCreatedAt(Instant v) { this.createdAt = v.toString(); }
    public void setUpdatedAt(Instant v) { this.updatedAt = v.toString(); }
}
