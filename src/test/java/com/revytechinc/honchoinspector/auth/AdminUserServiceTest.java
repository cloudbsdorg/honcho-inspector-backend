package com.revytechinc.honchoinspector.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserServiceTest {

    private UserDao users;
    private AuthSessionDao sessions;
    private AuthService auth;
    private PasswordHasher hasher;
    private AdminAudit audit;
    private JdbcTemplate jdbc;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        users = mock(UserDao.class);
        sessions = mock(AuthSessionDao.class);
        auth = mock(AuthService.class);
        hasher = mock(PasswordHasher.class);
        audit = mock(AdminAudit.class);
        jdbc = mock(JdbcTemplate.class);
        service = new AdminUserService(users, sessions, auth, hasher, audit);
    }

    @Test
    void create_blankUsername_returnsError() {
        var r = service.create("admin-id", "ip", "sid", "", "longpassword", null, null, null, false);
        assertThat(r.success()).isFalse();
        assertThat(r.kind()).isEqualTo(AdminUserService.ErrorKind.VALIDATION);
        assertThat(r.error()).contains("username");
        verify(audit, never()).record(anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void create_shortPassword_returnsError() {
        var r = service.create("admin-id", "ip", "sid", "alice", "short", null, null, null, false);
        assertThat(r.success()).isFalse();
        assertThat(r.kind()).isEqualTo(AdminUserService.ErrorKind.VALIDATION);
        assertThat(r.error()).contains("8 characters");
    }

    @Test
    void create_duplicateUsername_returnsError() {
        when(users.findByUsername("alice")).thenReturn(Optional.of(existingUser("alice", false)));
        var r = service.create("admin-id", "ip", "sid", "alice", "longpassword", null, null, null, false);
        assertThat(r.success()).isFalse();
        assertThat(r.kind()).isEqualTo(AdminUserService.ErrorKind.CONFLICT);
        assertThat(r.error()).contains("user already exists");
    }

    @Test
    void create_success_returnsUserAndAudits() {
        when(users.findByUsername("alice")).thenReturn(Optional.empty());
        var created = new User("u-alice", "alice", "hash", "A", "L", "a@x", false, Instant.now());
        when(auth.adminCreate(eq("alice"), eq("longpassword"), any(), any(), any(), eq(false)))
            .thenReturn(created);
        var r = service.create("admin-id", "ip", "sid", "alice", "longpassword", "A", "L", "a@x", false);
        assertThat(r.success()).isTrue();
        assertThat(r.user().username()).isEqualTo("alice");
        verify(audit, times(1)).record(eq("admin-id"), eq("user.create"), eq("u-alice"),
            any(), eq("ip"), eq("sid"), any());
    }

    @Test
    void update_notFound_returnsNotFound() {
        when(users.findById("u-x")).thenReturn(Optional.empty());
        var r = service.update("admin-id", "ip", "sid", "u-x", null, null, null, null, null);
        assertThat(r.found()).isFalse();
    }

    @Test
    void update_usernameCollision_returnsError() {
        var existing = existingUser("alice", false);
        when(users.findById("u-1")).thenReturn(Optional.of(existing));
        when(users.findByUsername("bob")).thenReturn(Optional.of(existingUser("bob", false)));
        var r = service.update("admin-id", "ip", "sid", "u-1", "bob", null, null, null, null);
        assertThat(r.found()).isTrue();
        assertThat(r.error()).contains("user already exists");
    }

    @Test
    void update_demoteLastAdmin_returnsError() {
        var adminUser = existingUser("u-admin", true);
        when(users.findById("u-admin")).thenReturn(Optional.of(adminUser));
        when(users.countByIsAdmin(true)).thenReturn(1L);
        var r = service.update("u-other", "ip", "sid", "u-admin", null, null, null, null, false);
        assertThat(r.error()).contains("last admin");
    }

    @Test
    void update_demoteSelf_returnsError() {
        var adminUser = existingUser("u-self", true);
        when(users.findById("u-self")).thenReturn(Optional.of(adminUser));
        when(users.countByIsAdmin(true)).thenReturn(2L);
        var r = service.update("u-self", "ip", "sid", "u-self", null, null, null, null, false);
        assertThat(r.error()).contains("cannot demote yourself");
    }

    @Test
    void update_success_auditsAndUpdates() {
        var u = existingUser("u-1", false);
        var updated = existingUser("u-1", false);
        when(users.findById("u-1")).thenReturn(Optional.of(u)).thenReturn(Optional.of(updated));
        when(users.countByIsAdmin(false)).thenReturn(5L);
        var r = service.update("admin-id", "ip", "sid", "u-1", "newname", "F", "L", "e@x", null);
        assertThat(r.found()).isTrue();
        assertThat(r.error()).isNull();
        verify(users).updateIdentity(eq("u-1"), eq("newname"), eq("F"), eq("L"), eq("e@x"));
        verify(users, never()).updateAdmin(anyString(), any(Boolean.class));
        verify(audit).record(eq("admin-id"), eq("user.update"), eq("u-1"), any(), eq("ip"), eq("sid"), any());
    }

    @Test
    void update_promoteToAdmin_callsUpdateAdmin() {
        var u = existingUser("u-1", false);
        var updated = existingUser("u-1", true);
        when(users.findById("u-1")).thenReturn(Optional.of(u)).thenReturn(Optional.of(updated));
        when(users.countByIsAdmin(true)).thenReturn(1L);
        var r = service.update("admin-id", "ip", "sid", "u-1", null, null, null, null, true);
        assertThat(r.error()).isNull();
        verify(users).updateAdmin("u-1", true);
    }

    @Test
    void delete_notFound_returnsNotFound() {
        when(users.findById("u-x")).thenReturn(Optional.empty());
        var r = service.delete("admin-id", "ip", "sid", "u-x");
        assertThat(r.found()).isFalse();
    }

    @Test
    void delete_lastAdmin_returnsError() {
        var adminUser = existingUser("u-admin", true);
        when(users.findById("u-admin")).thenReturn(Optional.of(adminUser));
        when(users.countByIsAdmin(true)).thenReturn(1L);
        var r = service.delete("u-other", "ip", "sid", "u-admin");
        assertThat(r.error()).contains("last admin");
        verify(users, never()).deleteById(anyString());
    }

    @Test
    void delete_self_returnsError() {
        var u = existingUser("u-self", false);
        when(users.findById("u-self")).thenReturn(Optional.of(u));
        var r = service.delete("u-self", "ip", "sid", "u-self");
        assertThat(r.error()).contains("cannot delete yourself");
    }

    @Test
    void delete_success_auditsAndDeletes() {
        var u = existingUser("u-1", false);
        when(users.findById("u-1")).thenReturn(Optional.of(u));
        var r = service.delete("admin-id", "ip", "sid", "u-1");
        assertThat(r.found()).isTrue();
        assertThat(r.error()).isNull();
        verify(users).deleteById("u-1");
        verify(audit).record(eq("admin-id"), eq("user.delete"), eq("u-1"), any(), eq("ip"), eq("sid"), any());
    }

    @Test
    void revokeAllSessions_userNotFound_returnsNegative() {
        when(users.findById("u-x")).thenReturn(Optional.empty());
        assertThat(service.revokeAllSessions("admin", "ip", "sid", "u-x")).isEqualTo(-1);
    }

    @Test
    void revokeAllSessions_success_returnsCount() {
        when(users.findById("u-1")).thenReturn(Optional.of(existingUser("u-1", false)));
        when(sessions.deleteByUserId("u-1")).thenReturn(3);
        assertThat(service.revokeAllSessions("admin", "ip", "sid", "u-1")).isEqualTo(3);
        verify(audit).record(eq("admin"), eq("user.sessions.revoke"), eq("u-1"), any(), eq("ip"), eq("sid"), any());
    }

    @Test
    void resetPassword_shortPassword_returnsFalse() {
        assertThat(service.resetPassword("admin", "ip", "sid", "u-1", "short")).isFalse();
        verify(users, never()).updatePasswordHash(anyString(), anyString());
    }

    @Test
    void resetPassword_userNotFound_returnsFalse() {
        when(users.findById("u-x")).thenReturn(Optional.empty());
        assertThat(service.resetPassword("admin", "ip", "sid", "u-x", "longpassword")).isFalse();
    }

    @Test
    void resetPassword_success_hashesAndRevokes() {
        when(users.findById("u-1")).thenReturn(Optional.of(existingUser("u-1", false)));
        when(hasher.hash("longpassword")).thenReturn("hashed");
        assertThat(service.resetPassword("admin", "ip", "sid", "u-1", "longpassword")).isTrue();
        verify(users).updatePasswordHash("u-1", "hashed");
        verify(sessions).deleteByUserId("u-1");
        verify(audit).record(eq("admin"), eq("user.password.reset"), eq("u-1"), any(), eq("ip"), eq("sid"), any());
    }

    @Test
    void list_passesThroughWithPageSizeRows() {
        when(users.count()).thenReturn(100L);
        when(users.listPaginated(20, 0)).thenReturn(List.of(existingUser("u-1", false)));
        var r = service.list(0, PageSize.S20);
        assertThat(r.rows()).isEqualTo(20);
        assertThat(r.total()).isEqualTo(100L);
        assertThat(r.items()).hasSize(1);
    }

    @Test
    void list_pageSizeAll_returnsAllInOnePage() {
        when(users.count()).thenReturn(7L);
        when(users.listPaginated(Integer.MAX_VALUE, 0)).thenReturn(List.of());
        var r = service.list(0, PageSize.ALL);
        assertThat(r.rows()).isEqualTo(Integer.MAX_VALUE);
        assertThat(r.pages()).isEqualTo(1);
    }

    @Test
    void search_emptyQuery_behavesLikeList() {
        when(users.count()).thenReturn(5L);
        when(users.listPaginated(10, 0)).thenReturn(List.of());
        var r = service.search("", 0, PageSize.S10);
        assertThat(r.total()).isEqualTo(5L);
    }

    @Test
    void search_nonEmptyQuery_usesSearch() {
        when(users.countSearchByQuery("alice")).thenReturn(2L);
        when(users.searchByQuery("alice", 20, 0)).thenReturn(List.of(existingUser("u-1", false)));
        var r = service.search("alice", 0, PageSize.S20);
        assertThat(r.total()).isEqualTo(2L);
        assertThat(r.items()).hasSize(1);
    }

    private static User existingUser(String id, boolean isAdmin) {
        return new User(id, "user-" + id, "hash", null, null, null, isAdmin, Instant.now());
    }
}
