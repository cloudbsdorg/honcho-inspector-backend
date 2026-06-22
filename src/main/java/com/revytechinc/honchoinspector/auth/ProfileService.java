package com.revytechinc.honchoinspector.auth;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class ProfileService {

    private final ProfileDao profiles;
    private final CryptoService crypto;
    private final SecureRandom rng = new SecureRandom();

    public ProfileService(ProfileDao profiles, CryptoService crypto) {
        this.profiles = profiles;
        this.crypto = crypto;
    }

    public List<Profile> list(String userId) {
        return profiles.findByUserId(userId);
    }

    public Profile create(String userId, String label, String apiKey,
                          String baseUrl, String workspaceId, String honchoUserName) {
        return create(userId, label, apiKey, baseUrl, workspaceId, honchoUserName, null);
    }

    public Profile create(String userId, String label, String apiKey,
                          String baseUrl, String workspaceId, String honchoUserName,
                          String apiVersion) {
        var now = Instant.now();
        var profile = new Profile(
            newId(),
            userId,
            label.trim(),
            crypto.encrypt(apiKey),
            baseUrl.trim(),
            workspaceId.trim(),
            honchoUserName.trim(),
            now,
            now,
            normalizeApiVersion(apiVersion)
        );
        profiles.insert(profile);
        return profile;
    }

    public Optional<Profile> get(String userId, String profileId) {
        var p = profiles.findById(profileId);
        if (p == null || !p.userId().equals(userId)) return Optional.empty();
        return Optional.of(p);
    }

    public Optional<ProfileWithKey> getWithKey(String userId, String profileId) {
        var p = profiles.findById(profileId);
        if (p == null || !p.userId().equals(userId)) return Optional.empty();
        return Optional.of(new ProfileWithKey(p, crypto.decrypt(p.apiKeyEncrypted())));
    }

    /**
     * Admin-scoped lookup: returns the profile with its decrypted API key
     * WITHOUT an ownership check. The caller MUST be an authenticated
     * admin (verified by {@link AdminAuthInterceptor}). Used by the
     * dashboard fan-out.
     */
    public Optional<ProfileWithKey> getWithKeyForAdmin(String profileId) {
        var p = profiles.findById(profileId);
        if (p == null) return Optional.empty();
        return Optional.of(new ProfileWithKey(p, crypto.decrypt(p.apiKeyEncrypted())));
    }

    public Optional<Profile> update(String userId, String profileId,
                                    String label, String apiKey,
                                    String baseUrl, String workspaceId, String honchoUserName) {
        return update(userId, profileId, label, apiKey, baseUrl, workspaceId, honchoUserName, null);
    }

    public Optional<Profile> update(String userId, String profileId,
                                    String label, String apiKey,
                                    String baseUrl, String workspaceId, String honchoUserName,
                                    String apiVersion) {
        var existing = profiles.findById(profileId);
        if (existing == null || !existing.userId().equals(userId)) return Optional.empty();
        var updated = new Profile(
            existing.id(),
            existing.userId(),
            label != null ? label.trim() : existing.label(),
            apiKey != null ? crypto.encrypt(apiKey) : existing.apiKeyEncrypted(),
            baseUrl != null ? baseUrl.trim() : existing.baseUrl(),
            workspaceId != null ? workspaceId.trim() : existing.workspaceId(),
            honchoUserName != null ? honchoUserName.trim() : existing.honchoUserName(),
            existing.createdAt(),
            Instant.now(),
            apiVersion != null ? normalizeApiVersion(apiVersion) : existing.apiVersion()
        );
        profiles.update(updated);
        return Optional.of(updated);
    }

    private static String normalizeApiVersion(String v) {
        if (v == null) return null;
        var trimmed = v.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public boolean delete(String userId, String profileId) {
        var p = profiles.findById(profileId);
        if (p == null || !p.userId().equals(userId)) return false;
        profiles.deleteById(profileId);
        return true;
    }

    private String newId() {
        var bytes = new byte[24];
        rng.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public record ProfileWithKey(Profile profile, String apiKey) {}
}
