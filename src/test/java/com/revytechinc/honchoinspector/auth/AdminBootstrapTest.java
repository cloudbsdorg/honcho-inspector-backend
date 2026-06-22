package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.config.HonchoProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminBootstrapTest {

    @Test
    void usersExist_skipsBootstrap() {
        var users = mock(UserDao.class);
        when(users.count()).thenReturn(5L);
        var auth = mock(AuthService.class);
        var audit = mock(AdminAudit.class);
        var jdbc = mock(JdbcTemplate.class);
        var props = new HonchoProperties(
            "https://api.honcho.dev", "v3", 30_000L,
            new HonchoProperties.Providers(false),
            new HonchoProperties.Log("INFO", "100MB", 30, "500MB"),
            new HonchoProperties.Bootstrap("admin", "longpassword", null, null, null),
            new HonchoProperties.Audit(90, 1_000_000L, "0 0 3 * * *")
        );
        new AdminBootstrap(props, users, auth, audit).bootstrap();
        verify(auth, never()).adminCreate(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void noUsersAndNoConfig_skipsBootstrap() {
        var users = mock(UserDao.class);
        when(users.count()).thenReturn(0L);
        var auth = mock(AuthService.class);
        var audit = mock(AdminAudit.class);
        var jdbc = mock(JdbcTemplate.class);
        var props = new HonchoProperties(
            "https://api.honcho.dev", "v3", 30_000L,
            new HonchoProperties.Providers(false),
            new HonchoProperties.Log("INFO", "100MB", 30, "500MB"),
            new HonchoProperties.Bootstrap(null, null, null, null, null),
            new HonchoProperties.Audit(90, 1_000_000L, "0 0 3 * * *")
        );
        new AdminBootstrap(props, users, auth, audit).bootstrap();
        verify(auth, never()).adminCreate(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void noUsersAndPartialConfig_skipsBootstrap() {
        var users = mock(UserDao.class);
        when(users.count()).thenReturn(0L);
        var auth = mock(AuthService.class);
        var audit = mock(AdminAudit.class);
        var jdbc = mock(JdbcTemplate.class);
        var props = new HonchoProperties(
            "https://api.honcho.dev", "v3", 30_000L,
            new HonchoProperties.Providers(false),
            new HonchoProperties.Log("INFO", "100MB", 30, "500MB"),
            new HonchoProperties.Bootstrap("admin", null, null, null, null),
            new HonchoProperties.Audit(90, 1_000_000L, "0 0 3 * * *")
        );
        new AdminBootstrap(props, users, auth, audit).bootstrap();
        verify(auth, never()).adminCreate(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void noUsersAndFullConfig_createsAdmin() {
        var users = mock(UserDao.class);
        when(users.count()).thenReturn(0L);
        var auth = mock(AuthService.class);
        var audit = mock(AdminAudit.class);
        var jdbc = mock(JdbcTemplate.class);
        var created = new User("u-admin", "admin", "h", "F", "L", "a@x", true, java.time.Instant.now());
        when(auth.adminCreate(
            org.mockito.ArgumentMatchers.eq("admin"),
            org.mockito.ArgumentMatchers.eq("longpassword"),
            org.mockito.ArgumentMatchers.eq("F"),
            org.mockito.ArgumentMatchers.eq("L"),
            org.mockito.ArgumentMatchers.eq("a@x"),
            org.mockito.ArgumentMatchers.eq(true))).thenReturn(created);
        var props = new HonchoProperties(
            "https://api.honcho.dev", "v3", 30_000L,
            new HonchoProperties.Providers(false),
            new HonchoProperties.Log("INFO", "100MB", 30, "500MB"),
            new HonchoProperties.Bootstrap("admin", "longpassword", "F", "L", "a@x"),
            new HonchoProperties.Audit(90, 1_000_000L, "0 0 3 * * *")
        );
        new AdminBootstrap(props, users, auth, audit).bootstrap();
        verify(auth, times(1)).adminCreate(
            org.mockito.ArgumentMatchers.eq("admin"),
            org.mockito.ArgumentMatchers.eq("longpassword"),
            org.mockito.ArgumentMatchers.eq("F"),
            org.mockito.ArgumentMatchers.eq("L"),
            org.mockito.ArgumentMatchers.eq("a@x"),
            org.mockito.ArgumentMatchers.eq(true));
        verify(audit, times(1)).record(
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("user.bootstrap"),
            org.mockito.ArgumentMatchers.eq("u-admin"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.any());
    }
}
