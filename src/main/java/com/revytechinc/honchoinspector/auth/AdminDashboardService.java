package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.model.HonchoContext;
import com.revytechinc.honchoinspector.service.HonchoProxyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

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

    private final UserDao users;
    private final ProfileDao profiles;
    private final AuthSessionDao sessions;
    private final AuditLogDao audit;
    private final HonchoProxyService honcho;
    private final ProfileService profileService;
    private final ExecutorService fanout = Executors.newFixedThreadPool(8, r -> {
        var t = new Thread(r, "admin-dashboard-fanout");
        t.setDaemon(true);
        return t;
    });

    public AdminDashboardService(
        UserDao users,
        ProfileDao profiles,
        AuthSessionDao sessions,
        AuditLogDao audit,
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
        m.put("usersLast7d", users.countSince(last7d));
        m.put("usersLast30d", users.countSince(last30d));
        m.put("profilesTotal", profiles.count());
        m.put("auditTotal", audit.count());
        m.put("auditLast30d", audit.countOlderThan(last30d));
        m.put("generatedAt", now.toString());
        return m;
    }

    public Map<String, Object> userDrilldown(String userId) {
        var user = users.findById(userId).orElse(null);
        if (user == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", UserResponse.from(user));
        m.put("profiles", profiles.findByUserId(userId).stream()
            .map(AdminDashboardService::profileToMap)
            .toList());
        m.put("sessions", sessions.findByUserId(userId).stream()
            .map(AdminDashboardService::sessionToMap)
            .toList());
        m.put("recentAudit", audit.search(new AuditLogDao.Query(userId, userId, null, null), 20, 0).stream()
            .map(AdminDashboardService::auditToMap)
            .toList());
        return m;
    }

    public Map<String, Object> honchoList() {
        List<Profile> all = profiles.findAll();
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
        var p = profiles.findById(profileId);
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
            m.put("queue", queue);
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

    private static Map<String, Object> auditToMap(AuditLogDao.Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("action", e.action());
        m.put("actorUserId", e.actorUserId());
        m.put("targetUserId", e.targetUserId());
        m.put("ip", e.ip());
        m.put("createdAt", e.createdAt().toString());
        return m;
    }
}
