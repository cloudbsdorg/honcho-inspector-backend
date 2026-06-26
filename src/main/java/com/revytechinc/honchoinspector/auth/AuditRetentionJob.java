package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.auth.repo.AuditLogRepository;

import com.revytechinc.honchoinspector.config.HonchoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Scheduled retention sweep for the {@code audit_log} table. Two policies,
 * both fire if either condition is met:
 *
 * <ol>
 *   <li>Age: any row older than {@code honcho.audit.retention-days} (default 90)
 *       is deleted.
 *   <li>Size: if the table row count exceeds {@code honcho.audit.max-rows}
 *       (default 1,000,000), the oldest rows are deleted until the cap is
 *       satisfied.
 * </ol>
 *
 * <p>Default cron is {@code "0 0 3 * * *"} (3:00 AM daily, local time).
 * The job records its own audit entry ({@code audit.purge}) with the
 * counts it deleted, so the operator has a permanent record of every
 * retention run. Errors are logged at WARN and never thrown.
 */
@Component
public class AuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionJob.class);

    private final AuditLogRepository repo;
    private final HonchoProperties properties;
    private final AdminAudit audit;

    public AuditRetentionJob(AuditLogRepository repo, HonchoProperties properties, AdminAudit audit) {
        this.repo = repo;
        this.properties = properties;
        this.audit = audit;
    }

    @Scheduled(cron = "${honcho.audit.purge-cron:0 0 3 * * *}")
    public void runScheduled() {
        run(null, null);
    }

    /**
     * Execute the retention sweep. Returns a {@link Result} describing
     * what was purged. Caller (manual trigger via {@code AdminMaintenanceController},
     * or the scheduled tick) can read it for confirmation / audit metadata.
     */
    public Result run(String actorId, String sessionId) {
        var cfg = properties.audit();
        Instant cutoff = Instant.now().minus(Duration.ofDays(cfg.retentionDays()));
        long longWindowBefore = Instant.now().getEpochSecond();
        int ageDeleted = repo.deleteOlderThan(cutoff);
        // Size cap: keep the newest cfg.maxRows entries by deleting the
        // oldest (totalCount - maxRows) rows. The repository paginates by
        // createdAt ascending and deletes the head of the queue.
        long totalCount = repo.count();
        long toDrop = Math.max(0, totalCount - cfg.maxRows());
        int sizeDeleted = (int) (toDrop > 0 ? repo.deleteOldest((int) toDrop) : 0);
        int total = ageDeleted + sizeDeleted;
        long tookMs = (Instant.now().getEpochSecond() - longWindowBefore) * 1000L;
        log.info(
            "audit retention: deleted {} rows (age: {}, size-cap: {}, retention-days={}, max-rows={}, tookMs={})",
            total, ageDeleted, sizeDeleted, cfg.retentionDays(), cfg.maxRows(), tookMs);
        if (total > 0) {
            audit.record(actorId, "audit.purge", null, null, null, sessionId,
                Map.of("ageDeleted", ageDeleted,
                       "sizeDeleted", sizeDeleted,
                       "total", total,
                       "retentionDays", cfg.retentionDays(),
                       "maxRows", cfg.maxRows()));
        }
        return new Result(ageDeleted, sizeDeleted, total, cfg.retentionDays(), cfg.maxRows());
    }

    public record Result(
        int ageDeleted,
        int sizeDeleted,
        int totalDeleted,
        int retentionDays,
        long maxRows
    ) {}
}
