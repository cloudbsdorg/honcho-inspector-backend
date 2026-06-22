package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.service.HonchoProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminDashboardServiceTest {

    private UserDao users;
    private ProfileDao profiles;
    private AuthSessionDao sessions;
    private AuditLogDao audit;
    private HonchoProxyService honcho;
    private ProfileService profileService;
    private JdbcTemplate jdbc;
    private AdminDashboardService service;

    @BeforeEach
    void setUp() {
        users = mock(UserDao.class);
        profiles = mock(ProfileDao.class);
        sessions = mock(AuthSessionDao.class);
        audit = mock(AuditLogDao.class);
        honcho = mock(HonchoProxyService.class);
        profileService = mock(ProfileService.class);
        jdbc = mock(JdbcTemplate.class);
        service = new AdminDashboardService(users, profiles, sessions, audit, honcho, profileService);
    }

    @Test
    void overview_returnsAllAggregates() {
        when(users.count()).thenReturn(42L);
        when(users.countByIsAdmin(true)).thenReturn(2L);
        when(users.countSince(any())).thenReturn(3L);
        when(profiles.count()).thenReturn(7L);
        when(audit.count()).thenReturn(100L);
        when(audit.countOlderThan(any())).thenReturn(50L);

        var m = service.overview();

        assertThat(m).containsEntry("usersTotal", 42L)
                    .containsEntry("usersAdmins", 2L)
                    .containsEntry("profilesTotal", 7L)
                    .containsEntry("auditTotal", 100L)
                    .containsEntry("auditLast30d", 50L);
        assertThat(m).containsKey("usersLast7d");
        assertThat(m).containsKey("usersLast30d");
        assertThat(m).containsKey("generatedAt");
    }

    @Test
    void userDrilldown_returnsNullForUnknownUser() {
        when(users.findById("u-x")).thenReturn(Optional.empty());
        assertThat(service.userDrilldown("u-x")).isNull();
    }

    @Test
    void userDrilldown_returnsBundledView() {
        var u = new User("u-1", "alice", "hash", "F", "L", "e@x", false, Instant.now());
        when(users.findById("u-1")).thenReturn(Optional.of(u));
        when(profiles.findByUserId("u-1")).thenReturn(List.of());
        when(sessions.findByUserId("u-1")).thenReturn(List.of());
        when(audit.search(any(), org.mockito.ArgumentMatchers.eq(20), org.mockito.ArgumentMatchers.eq(0)))
            .thenReturn(List.of());

        var m = service.userDrilldown("u-1");

        assertThat(m).containsKey("user");
        assertThat(m).containsKey("profiles");
        assertThat(m).containsKey("sessions");
        assertThat(m).containsKey("recentAudit");
    }

    @Test
    void honchoList_emptyProfiles_returnsEmpty() {
        when(profiles.findAll()).thenReturn(List.of());
        var m = service.honchoList();
        assertThat(m).containsEntry("reachable", 0)
                    .containsEntry("unreachable", 0);
        assertThat((List<?>) m.get("profiles")).isEmpty();
    }

    @Test
    void honchoList_marksReachable() throws Exception {
        var p1 = newProfile("p-1");
        var p2 = newProfile("p-2");
        when(profiles.findAll()).thenReturn(List.of(p1, p2));
        when(profileService.getWithKeyForAdmin("p-1")).thenReturn(Optional.of(
            new ProfileService.ProfileWithKey(p1, "key-1")));
        when(profileService.getWithKeyForAdmin("p-2")).thenReturn(Optional.of(
            new ProfileService.ProfileWithKey(p2, "key-2")));
        var callCount = new AtomicInteger();
        when(honcho.getQueueStatus(any())).thenAnswer(inv -> {
            int n = callCount.incrementAndGet();
            if (n == 1) return Map.of("total", 0, "completed", 0);
            throw new HonchoCallException("connection refused", 502, "{\"detail\":\"down\"}");
        });

        var m = service.honchoList();

        assertThat(m).containsEntry("reachable", 1)
                    .containsEntry("unreachable", 1);
        List<?> list = (List<?>) m.get("profiles");
        assertThat(list).hasSize(2);
    }

    @Test
    void honchoList_partialTimeout_doesNotFailOtherProfiles() throws Exception {
        var p1 = newProfile("p-1");
        var p2 = newProfile("p-2");
        when(profiles.findAll()).thenReturn(List.of(p1, p2));
        when(profileService.getWithKeyForAdmin("p-1")).thenReturn(Optional.of(
            new ProfileService.ProfileWithKey(p1, "key-1")));
        when(profileService.getWithKeyForAdmin("p-2")).thenReturn(Optional.of(
            new ProfileService.ProfileWithKey(p2, "key-2")));
        when(honcho.getQueueStatus(any())).thenReturn(Map.of("total", 0));

        var m = service.honchoList();

        assertThat(m).containsEntry("reachable", 2)
                    .containsEntry("unreachable", 0);
    }

    @Test
    void honchoDrilldown_returnsNullForUnknownProfile() {
        when(profiles.findById("p-x")).thenReturn(null);
        assertThat(service.honchoDrilldown("p-x")).isNull();
    }

    @Test
    void honchoDrilldown_returnsQueueAndWorkspace() throws Exception {
        var p = newProfile("p-1");
        when(profiles.findById("p-1")).thenReturn(p);
        when(profileService.getWithKeyForAdmin("p-1")).thenReturn(Optional.of(
            new ProfileService.ProfileWithKey(p, "key-1")));
        when(honcho.getQueueStatus(any())).thenReturn(Map.of("total", 3));
        when(honcho.getWorkspaceInfo(any())).thenReturn(Map.of("workspace", Map.of("id", "ws-1")));

        var m = service.honchoDrilldown("p-1");

        assertThat(m).containsEntry("reachable", true);
        assertThat(m).containsKey("queue");
        assertThat(m).containsKey("workspace");
    }

    @Test
    void honchoDrilldown_unreachable_returnsError() throws Exception {
        var p = newProfile("p-1");
        when(profiles.findById("p-1")).thenReturn(p);
        when(profileService.getWithKeyForAdmin("p-1")).thenReturn(Optional.of(
            new ProfileService.ProfileWithKey(p, "key-1")));
        when(honcho.getQueueStatus(any())).thenThrow(new HonchoCallException("down", 502, "{}"));

        var m = service.honchoDrilldown("p-1");

        assertThat(m).containsEntry("reachable", false);
        assertThat(m).containsKey("error");
    }

    private static Profile newProfile(String id) {
        return new Profile(id, "u-owner", "label-" + id, "encrypted", "https://api.honcho.dev",
            "ws-1", "revytech", Instant.now(), Instant.now(), null);
    }
}
