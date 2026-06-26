package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.auth.entity.AuthSessionEntity;
import com.revytechinc.honchoinspector.auth.entity.AuditLogEntity;
import com.revytechinc.honchoinspector.auth.entity.UserEntity;
import com.revytechinc.honchoinspector.auth.repo.AuditLogRepository;
import com.revytechinc.honchoinspector.auth.repo.AuthSessionRepository;
import com.revytechinc.honchoinspector.auth.repo.ProfileRepository;
import com.revytechinc.honchoinspector.auth.repo.AuditLogSpecifications;
import com.revytechinc.honchoinspector.auth.repo.UserRepository;
import com.revytechinc.honchoinspector.auth.repo.UserSpecifications;
import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.model.HonchoContext;
import com.revytechinc.honchoinspector.service.HonchoProxyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Aggregate service for the admin dashboard. Pure local SQL aggregates +
 * parallel fan-out to all registered Honcho profiles with per-profile
 * 5-second timeout. Partial failures (one profile down) do not fail
 * the whole dashboard; the unreachable profile is marked as such and
 * the rest are returned.
 */
@Service
public class AdminDashboardService {

    private static final Logger log = LoggerFactory.getLogger(AdminDashboardService.class);
    private static final Duration PER_PROFILE_TIMEOUT = Duration.ofSeconds(5);

    private final UserRepository users;
    private final ProfileRepository profiles;
    private final AuthSessionRepository sessions;
    private final AuditLogRepository audit;
    private final HonchoProxyService honcho;
    private final ProfileService profileService;
    private final ExecutorService fanout = Executors.newFixedThreadPool(8, r -> {
        var t = new Thread(r, "admin-dashboard-fanout");
        t.setDaemon(true);
        return t;
    });

    public AdminDashboardService(
        UserRepository users,
        ProfileRepository profiles,
        AuthSessionRepository sessions,
        AuditLogRepository audit,
        HonchoProxyService honcho,
        ProfileService profileService
    ) {
        this.users = users;
        this.profiles = profiles;
        this.sessions = sessions;
        this.audit = audit;
        this.honcho = honcho;
        this.profileService = profileService;
    }

    public Map<String, Object> overview() {
        Instant now = Instant.now();
        Instant last7d = now.minus(Duration.ofDays(7));
        Instant last30d = now.minus(Duration.ofDays(30));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("usersTotal", users.count());
        m.put("usersAdmins", users.countByIsAdmin(true));
        m.put("usersLast7d", users.count(UserSpecifications.createdAtOrAfter(last7d)));
        m.put("usersLast30d", users.count(UserSpecifications.createdAtOrAfter(last30d)));
        m.put("profilesTotal", profiles.count());
        m.put("auditTotal", audit.count());
        m.put("auditLast30d", audit.countAtOrAfter(last30d));
        m.put("generatedAt", now.toString());
        return m;
    }

    public Map<String, Object> userDrilldown(String userId) {
        var user = users.findById(userId).map(AdminDashboardService::toRecord).orElse(null);
        if (user == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", UserResponse.from(user));
        m.put("profiles", profiles.findByUserId(userId).stream()
            .map(p -> profileToMap(toRecord(p)))
            .toList());
        m.put("sessions", sessions.findByUserId(userId).stream()
            .map(s -> sessionToMap(toRecord(s)))
            .toList());
        Page<AuditLogEntity> recent = audit.findAll(
            AuditLogSpecifications.all(null, userId, userId, null),
            PageRequest.of(0, 20));
        m.put("recentAudit", recent.getContent().stream()
            .map(AdminDashboardService::auditToMap)
            .toList());
        return m;
    }


    public Map<String, Object> honchoList() {
        List<Profile> all = profiles.findAll().stream()
            .map(AdminDashboardService::toRecord)
            .toList();
        if (all.isEmpty()) {
            return Map.of("profiles", List.of(), "reachable", 0, "unreachable", 0);
        }
        var futures = all.stream()
            .map(p -> CompletableFuture.supplyAsync(() -> probe(p), fanout)
                .completeOnTimeout(Map.of(
                    "profileId", p.id(),
                    "label", p.label(),
                    "baseUrl", p.baseUrl(),
                    "workspaceId", p.workspaceId(),
                    "reachable", false,
                    "error", "timeout after 5s"
                ), PER_PROFILE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
            .toList();
        var results = futures.stream()
            .map(CompletableFuture::join)
            .toList();
        int reachable = (int) results.stream().filter(r -> Boolean.TRUE.equals(r.get("reachable"))).count();
        int unreachable = results.size() - reachable;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("profiles", results);
        m.put("reachable", reachable);
        m.put("unreachable", unreachable);
        m.put("generatedAt", Instant.now().toString());
        return m;
    }

    public Map<String, Object> honchoDrilldown(String profileId) {
        var p = profiles.findById(profileId)
            .map(AdminDashboardService::toRecord).orElse(null);
        if (p == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("profile", profileToMap(p));
        m.put("reachable", false);
        try {
            var pwk = profileService.getWithKeyForAdmin(p.id())
                .orElseThrow(() -> new IllegalStateException("profile disappeared"));
            var ctx = buildContext(pwk);
            var queue = honcho.getQueueStatus(ctx);
            var workspace = honcho.getWorkspaceInfo(ctx);
            m.put("reachable", true);
            m.put("queue", queue);
            m.put("workspace", workspace);
        } catch (Exception e) {
            log.warn("honcho profile {} probe failed: {}", p.id(), e.getMessage());
            m.put("error", e.getMessage());
        }
        m.put("generatedAt", Instant.now().toString());
        return m;
    }

    private Map<String, Object> probe(Profile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("profileId", p.id());
        m.put("label", p.label());
        m.put("baseUrl", p.baseUrl());
        m.put("workspaceId", p.workspaceId());
        try {
            var pwk = profileService.getWithKeyForAdmin(p.id())
                .orElseThrow(() -> new IllegalStateException("profile disappeared"));
            var ctx = buildContext(pwk);
            var queue = honcho.getQueueStatus(ctx);
            m.put("reachable", true);
        } catch (Exception e) {
            m.put("reachable", false);
            String msg = e.getMessage();
            m.put("error", msg == null ? e.getClass().getSimpleName() : msg);
        }
        return m;
    }

    private static HonchoContext buildContext(ProfileService.ProfileWithKey pwk) {
        HonchoApiVersion apiVersion = HonchoApiVersion.V3;
        String raw = pwk.profile().apiVersion();
        if (raw != null && !raw.isBlank()) {
            try {
                apiVersion = HonchoApiVersion.fromString(raw);
            } catch (IllegalArgumentException e) {
                apiVersion = HonchoApiVersion.V3;
            }
        }
        return new HonchoContext(
            pwk.apiKey(),
            pwk.profile().baseUrl(),
            pwk.profile().workspaceId(),
            pwk.profile().honchoUserName(),
            apiVersion,
            pwk.profile().id()
        );
    }

    static Profile toRecord(com.revytechinc.honchoinspector.auth.entity.ProfileEntity e) {
        return new Profile(
            e.getId(), e.getUserId(), e.getLabel(),
            e.getApiKeyEncrypted(), e.getBaseUrl(),
            e.getWorkspaceId(), e.getHonchoUserName(),
            e.getCreatedAtAsInstant(), e.getUpdatedAtAsInstant(),
            e.getApiVersion()
        );
    }

    private static User toRecord(UserEntity e) {
        return new User(
            e.getId(), e.getUsername(), e.getPasswordHash(),
            e.getFirstname(), e.getLastname(), e.getEmail(),
            e.getIsAdmin(), e.getCreatedAtAsInstant()
        );
    }

    private static AuthSession toRecord(AuthSessionEntity e) {
        return new AuthSession(
            e.getId(), e.getUserId(),
            e.getCreatedAtAsInstant(), e.getLastSeenAtAsInstant(),
            e.getExpiresAtAsInstant() == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(e.getExpiresAtAsInstant()));
    }

    private static Map<String, Object> profileToMap(Profile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("label", p.label());
        m.put("baseUrl", p.baseUrl());
        m.put("workspaceId", p.workspaceId());
        m.put("honchoUserName", p.honchoUserName());
        m.put("createdAt", p.createdAt().toString());
        m.put("updatedAt", p.updatedAt().toString());
        return m;
    }

    private static Map<String, Object> sessionToMap(AuthSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("createdAt", s.createdAt().toString());
        m.put("lastSeenAt", s.lastSeenAt().toString());
        m.put("expiresAt", s.expiresAt().map(Instant::toString).orElse(null));
        return m;
    }

    private static Map<String, Object> sessionToMap(AuthSessionEntity e) {
        return sessionToMap(new AuthSession(
            e.getId(), e.getUserId(),
            e.getCreatedAtAsInstant(), e.getLastSeenAtAsInstant(),
            e.getExpiresAtAsInstant() == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(e.getExpiresAtAsInstant())
        ));
    }

    private static Map<String, Object> auditToMap(AuditLogEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("action", e.getAction());
        m.put("actorUserId", e.getActorUserId());
        m.put("targetUserId", e.getTargetUserId());
        m.put("ip", e.getIp());
        m.put("createdAt", e.getCreatedAtAsInstant().toString());
        return m;
    }
}
