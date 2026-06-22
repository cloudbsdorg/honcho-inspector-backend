package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.config.HonchoProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditRetentionJobTest {

    @Test
    void ageWindow_deletesOlderRows() {
        var dao = mock(AuditLogDao.class);
        when(dao.deleteOlderThan(org.mockito.ArgumentMatchers.any())).thenReturn(7);
        when(dao.deleteOldestKeeping(100L)).thenReturn(0);
        var audit = mock(AdminAudit.class);
        var props = newProps(30, 100L, "0 0 3 * * *");

        var result = new AuditRetentionJob(dao, props, audit).run("admin", "sid");

        assertThat(result.ageDeleted()).isEqualTo(7);
        assertThat(result.sizeDeleted()).isZero();
        assertThat(result.totalDeleted()).isEqualTo(7);
        assertThat(result.retentionDays()).isEqualTo(30);
        verify(audit, times(1)).record(
            org.mockito.ArgumentMatchers.eq("admin"),
            org.mockito.ArgumentMatchers.eq("audit.purge"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("sid"),
            org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sizeCap_deletesOldestExcess() {
        var dao = mock(AuditLogDao.class);
        when(dao.deleteOlderThan(org.mockito.ArgumentMatchers.any())).thenReturn(0);
        when(dao.deleteOldestKeeping(50L)).thenReturn(3);
        var audit = mock(AdminAudit.class);
        var props = newProps(90, 50L, "0 0 3 * * *");

        var result = new AuditRetentionJob(dao, props, audit).run(null, null);

        assertThat(result.ageDeleted()).isZero();
        assertThat(result.sizeDeleted()).isEqualTo(3);
        assertThat(result.totalDeleted()).isEqualTo(3);
        verify(audit, times(1)).record(
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("audit.purge"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.any());
    }

    @Test
    void noRowsDeleted_doesNotAudit() {
        var dao = mock(AuditLogDao.class);
        when(dao.deleteOlderThan(org.mockito.ArgumentMatchers.any())).thenReturn(0);
        when(dao.deleteOldestKeeping(1000L)).thenReturn(0);
        var audit = mock(AdminAudit.class);
        var props = newProps(90, 1000L, "0 0 3 * * *");

        var result = new AuditRetentionJob(dao, props, audit).run("admin", "sid");

        assertThat(result.totalDeleted()).isZero();
        verify(audit, never()).record(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    }

    @Test
    void auditMetadataIncludesDeletedCounts() {
        var dao = mock(AuditLogDao.class);
        when(dao.deleteOlderThan(org.mockito.ArgumentMatchers.any())).thenReturn(5);
        when(dao.deleteOldestKeeping(100L)).thenReturn(2);
        var audit = mock(AdminAudit.class);
        var props = newProps(30, 100L, "0 0 3 * * *");

        new AuditRetentionJob(dao, props, audit).run("admin", "sid");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> meta = ArgumentCaptor.forClass(java.util.Map.class);
        verify(audit, times(1)).record(
            org.mockito.ArgumentMatchers.eq("admin"),
            org.mockito.ArgumentMatchers.eq("audit.purge"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("sid"),
            meta.capture());
        assertThat(meta.getValue()).containsEntry("ageDeleted", 5)
                                    .containsEntry("sizeDeleted", 2)
                                    .containsEntry("total", 7)
                                    .containsEntry("retentionDays", 30)
                                    .containsEntry("maxRows", 100L);
    }

    private static HonchoProperties newProps(int retentionDays, long maxRows, String cron) {
        return new HonchoProperties(
            "https://api.honcho.dev", "v3", 30_000L,
            new HonchoProperties.Providers(false),
            new HonchoProperties.Log("INFO", "100MB", 30, "500MB"),
            new HonchoProperties.Bootstrap(null, null, null, null, null),
            new HonchoProperties.Audit(retentionDays, maxRows, cron)
        );
    }
}
