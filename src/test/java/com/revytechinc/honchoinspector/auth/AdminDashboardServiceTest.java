package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.auth.entity.AuditLogEntity;
import com.revytechinc.honchoinspector.auth.entity.ProfileEntity;
import com.revytechinc.honchoinspector.auth.repo.AuditLogRepository;
import com.revytechinc.honchoinspector.auth.repo.AuthSessionRepository;
import com.revytechinc.honchoinspector.auth.repo.ProfileRepository;
import com.revytechinc.honchoinspector.auth.repo.UserRepository;
import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.service.HonchoProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

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

    private UserRepository users;
    private ProfileRepository profiles;
    private AuthSessionRepository sessions;
    private AuditLogRepository audit;
    private HonchoProxyService honcho;
    private ProfileService profileService;
    private AdminDashboardService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        profiles = mock(ProfileRepository.class);
        sessions = mock(AuthSessionRepository.class);
        audit = mock(AuditLogRepository.class);
        honcho = mock(HonchoProxyService.class);
        profileService = mock(ProfileService.class);
        service = new AdminDashboardService(users, profiles, sessions, audit, honcho, profileService);
    }

    @Test
    void overview_returnsAllAggregates() {
        when(users.count()).thenReturn(42L);
        when(users.countByIsAdmin(true)).thenReturn(2L);
        when(users.count(any(org.springframework.data.jpa.domain.Specification.class)))
            .thenReturn(3L, 5L, 12L);
        when(profiles.count()).thenReturn(7L);
        when(audit.count()).thenReturn(100L);
        when(audit.countAtOrAfter(any())).thenReturn(50L);

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
        var userEntityInst = new com.revytechinc.honchoinspector.auth.entity.UserEntity(
            "u-1", "alice", "hash", "F", "L", "e@x", false, Instant.now());
        when(users.findById("u-1")).thenReturn(Optional.of(userEntityInst));
        when(profiles.findByUserId("u-1")).thenReturn(List.of());
        when(sessions.findByUserId("u-1")).thenReturn(List.of());
        when(audit.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
            .thenReturn((Page<AuditLogEntity>) new PageImpl<AuditLogEntity>(List.of()));

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
        var p1 = profileEntity("p-1");
        var p2 = profileEntity("p-2");
        when(profiles.findAll()).thenReturn(List.of(p1, p2));
        when(profileService.getWithKeyForAdmin("p-1")).thenReturn(Optional.of(
            new ProfileService.ProfileWithKey(AdminDashboardService.toRecord(p1), "key-1")));
        when(profileService.getWithKeyForAdmin("p-2")).thenReturn(Optional.of(
            new ProfileService.ProfileWithKey(AdminDashboardService.toRecord(p2), "key-2")));
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
        var p1 = profileEntity("p-1");
        var p2 = profileEntity("p-2");
        when(profiles.findAll()).thenReturn(List.of(p1, p2));
        when(profileService.getWithKeyForAdmin("p-1")).thenReturn(Optional.of(
            new ProfileService.ProfileWithKey(AdminDashboardService.toRecord(p1), "key-1")));
        when(profileService.getWithKeyForAdmin("p-2")).thenReturn(Optional.of(
            new ProfileService.ProfileWithKey(AdminDashboardService.toRecord(p2), "key-2")));
        when(honcho.getQueueStatus(any())).thenReturn(Map.of("total", 0));

        var m = service.honchoList();

        assertThat(m).containsEntry("reachable", 2)
                     .containsEntry("unreachable", 0);
    }

    @Test
    void honchoDrilldown_returnsNullForUnknownProfile() {
        when(profiles.findById("p-x")).thenReturn(Optional.empty());
        assertThat(service.honchoDrilldown("p-x")).isNull();
    }

    @Test
    void honchoDrilldown_returnsQueueAndWorkspace() throws Exception {
        var p = profileEntity("p-1");
        when(profiles.findById("p-1")).thenReturn(Optional.of(p));
        when(profileService.getWithKeyForAdmin("p-1")).thenReturn(Optional.of(
            new ProfileService.ProfileWithKey(AdminDashboardService.toRecord(p), "key-1")));
        when(honcho.getQueueStatus(any())).thenReturn(Map.of("total", 3));
        when(honcho.getWorkspaceInfo(any())).thenReturn(Map.of("workspace", Map.of("id", "ws-1")));

        var m = service.honchoDrilldown("p-1");

        assertThat(m).containsEntry("reachable", true);
        assertThat(m).containsKey("queue");
        assertThat(m).containsKey("workspace");
    }

    @Test
    void honchoDrilldown_unreachable_returnsError() throws Exception {
        var p = profileEntity("p-1");
        when(profiles.findById("p-1")).thenReturn(Optional.of(p));
        when(profileService.getWithKeyForAdmin("p-1")).thenReturn(Optional.of(
            new ProfileService.ProfileWithKey(AdminDashboardService.toRecord(p), "key-1")));
        when(honcho.getQueueStatus(any())).thenThrow(new HonchoCallException("down", 502, "{}"));

        var m = service.honchoDrilldown("p-1");

        assertThat(m).containsEntry("reachable", false);
        assertThat(m).containsKey("error");
    }

    private static ProfileEntity profileEntity(String id) {
        return new ProfileEntity(id, "u-owner", "label-" + id, "encrypted", "https://api.honcho.dev",
            "ws-1", "revytech", null, Instant.now(), Instant.now());
    }
}
