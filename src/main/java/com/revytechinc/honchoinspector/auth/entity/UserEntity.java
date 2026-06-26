package com.revytechinc.honchoinspector.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Convert;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Backend admin user. Mapped onto the existing `users` table — JPA
 * runs in `validate` mode so the column names here are checked
 * against the SQL schema on every startup but never mutated.
 *
 * Stored passwords are bcrypt hashes (cost 12) — never the raw
 * plaintext.
 */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "username", nullable = false, unique = true, length = 128)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 128)
    private String passwordHash;

    @Column(name = "firstname", length = 128)
    private String firstname;

    @Column(name = "lastname", length = 128)
    private String lastname;

    @Column(name = "email", length = 256)
    private String email;

    @Column(name = "is_admin", nullable = false, columnDefinition = "INTEGER")
    private boolean isAdmin;

    @Column(name = "created_at", nullable = false, length = 32)
    private String createdAt;

    public UserEntity() {}

    public UserEntity(
        String id, String username, String passwordHash,
        String firstname, String lastname, String email,
        boolean isAdmin, Instant createdAt
    ) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.firstname = firstname;
        this.lastname = lastname;
        this.email = email;
        this.isAdmin = isAdmin;
        this.createdAt = createdAt.toString();
    }

    public String getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getFirstname() { return firstname; }
    public String getLastname() { return lastname; }
    public String getEmail() { return email; }
    public boolean getIsAdmin() { return isAdmin; }
    public String getCreatedAt() { return createdAt; }
    public Instant getCreatedAtAsInstant() { return Instant.parse(createdAt); }

    public void setId(String id) { this.id = id; }
    public void setUsername(String v) { this.username = v; }
    public void setPasswordHash(String v) { this.passwordHash = v; }
    public void setFirstname(String v) { this.firstname = v; }
    public void setLastname(String v) { this.lastname = v; }
    public void setEmail(String v) { this.email = v; }
    public void setIsAdmin(boolean v) { this.isAdmin = v; }
    public void setCreatedAt(Instant v) { this.createdAt = v.toString(); }
}
