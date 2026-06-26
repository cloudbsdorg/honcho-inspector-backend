package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.auth.entity.ProfileEntity;
import com.revytechinc.honchoinspector.auth.repo.ProfileRepository;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class ProfileService {

    private final ProfileRepository repo;
    private final CryptoService crypto;
    private final SecureRandom rng = new SecureRandom();

    public ProfileService(ProfileRepository repo, CryptoService crypto) {
        this.repo = repo;
        this.crypto = crypto;
    }

    public List<Profile> list(String userId) {
        return repo.findByUserId(userId).stream().map(ProfileService::toRecord).toList();
    }

    public Profile create(String userId, String label, String apiKey,
                          String baseUrl, String workspaceId, String honchoUserName) {
        return create(userId, label, apiKey, baseUrl, workspaceId, honchoUserName, null);
    }

    public Profile create(String userId, String label, String apiKey,
                          String baseUrl, String workspaceId, String honchoUserName,
                          String apiVersion) {
        var now = Instant.now();
        var entity = new ProfileEntity(
            newId(), userId, label, crypto.encrypt(apiKey),
            baseUrl, workspaceId, honchoUserName, normalizeApiVersion(apiVersion),
            now, now);
        repo.save(entity);
        return toRecord(entity);
    }

    public Optional<Profile> get(String userId, String profileId) {
        return repo.findById(profileId)
            .filter(p -> p.getUserId().equals(userId))
            .map(ProfileService::toRecord);
    }

    public Optional<ProfileWithKey> getWithKey(String userId, String profileId) {
        return repo.findById(profileId)
            .filter(p -> p.getUserId().equals(userId))
            .map(p -> new ProfileWithKey(toRecord(p), crypto.decrypt(p.getApiKeyEncrypted())));
    }

    /**
     * Admin-scoped lookup: returns the profile with its decrypted API key
     * WITHOUT an ownership check. The caller MUST be an authenticated
     * admin (verified by {@link AdminAuthInterceptor}). Used by the
     * dashboard fan-out.
     */
    public Optional<ProfileWithKey> getWithKeyForAdmin(String profileId) {
        return repo.findById(profileId)
            .map(p -> new ProfileWithKey(toRecord(p), crypto.decrypt(p.getApiKeyEncrypted())));
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
        var existing = repo.findById(profileId).orElse(null);
        if (existing == null || !existing.getUserId().equals(userId)) return Optional.empty();
        var updated = repo.save(new ProfileEntity(
            existing.getId(),
            existing.getUserId(),
            label != null ? label.trim() : existing.getLabel(),
            apiKey != null ? crypto.encrypt(apiKey) : existing.getApiKeyEncrypted(),
            baseUrl != null ? baseUrl.trim() : existing.getBaseUrl(),
            workspaceId != null ? workspaceId.trim() : existing.getWorkspaceId(),
            honchoUserName != null ? honchoUserName.trim() : existing.getHonchoUserName(),
            apiVersion != null ? normalizeApiVersion(apiVersion) : existing.getApiVersion(),
            existing.getCreatedAtAsInstant(),
            Instant.now()
        ));
        return Optional.of(toRecord(updated));
    }

    private static String normalizeApiVersion(String v) {
        if (v == null) return null;
        var trimmed = v.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public boolean delete(String userId, String profileId) {
        var p = repo.findById(profileId).orElse(null);
        if (p == null || !p.getUserId().equals(userId)) return false;
        repo.deleteById(profileId);
        return true;
    }

    private String newId() {
        var bytes = new byte[24];
        rng.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static Profile toRecord(ProfileEntity e) {
        return new Profile(
            e.getId(), e.getUserId(), e.getLabel(),
            e.getApiKeyEncrypted(), e.getBaseUrl(),
            e.getWorkspaceId(), e.getHonchoUserName(),
            e.getCreatedAtAsInstant(), e.getUpdatedAtAsInstant(),
            e.getApiVersion()
        );
    }

    public record ProfileWithKey(Profile profile, String apiKey) {}
}
