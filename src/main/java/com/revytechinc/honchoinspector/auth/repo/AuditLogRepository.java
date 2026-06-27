package com.revytechinc.honchoinspector.auth.repo;

import com.revytechinc.honchoinspector.auth.entity.AuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface AuditLogRepository
        extends JpaRepository<AuditLogEntity, String>,
                JpaSpecificationExecutor<AuditLogEntity> {

    /**
     * Count entries that would be deleted by deleteOlderThan. The
     * retention job uses this for telemetry; the actual deletion is
     * a per-row loop driven by the returned count.
     */
    default long countOlderThan(Instant cutoff) {
        String iso = cutoff.toString();
        return count((root, cq, cb) -> cb.lessThan(root.<String>get("createdAt"), iso));
    }

    /**
     * Count rows with createdAt >= since. Pure Specification path
     * — no @Query.
     */
    default long countAtOrAfter(Instant since) {
        String iso = since.toString();
        return count((root, cq, cb) -> cb.greaterThanOrEqualTo(root.<String>get("createdAt"), iso));
    }

    /**
     * Delete the oldest N rows (by createdAt ascending). Used by the
     * audit size-cap policy: when the table exceeds maxRows, drop
     * the oldest excess rows. Uses Specification + manual iteration
     * to avoid a custom @Query.
     */
    default int deleteOldest(int n) {
        if (n <= 0) return 0;
        int deleted = 0;
        var page = findAll(
            (root, cq, cb) -> cb.conjunction(),
            org.springframework.data.domain.PageRequest.of(0, n,
                org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Direction.ASC, "createdAt")));
        for (var e : page) {
            deleteById(e.getId());
            deleted++;
        }
        return deleted;
    }
}

