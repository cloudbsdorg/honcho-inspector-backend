package com.honcho.dashboard.auth;

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
            now
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

    public Optional<Profile> update(String userId, String profileId,
                                    String label, String apiKey,
                                    String baseUrl, String workspaceId, String honchoUserName) {
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
            Instant.now()
        );
        profiles.update(updated);
        return Optional.of(updated);
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
