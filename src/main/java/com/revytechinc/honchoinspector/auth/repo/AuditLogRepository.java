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
     * Delete every entry with createdAt < cutoff. Iterates in Java
     * because the table is small (default cap: 1,000,000 rows) and
     * a single bulk DELETE per 1000-row batch is plenty fast.
     */
    default int deleteOlderThan(Instant cutoff) {
        int n = 0;
        for (var e : findAll(
            (root, cq, cb) -> cb.lessThan(root.<String>get("createdAt"), cutoff))) {
            deleteById(e.getId());
            n++;
        }
        return n;
    }

    /**
     * Count rows with createdAt >= since. Pure Specification path
     * — no @Query.
     */
    default long countAtOrAfter(Instant since) {
        if (since == null) return count();
        return count(
            (root, cq, cb) -> cb.greaterThanOrEqualTo(
                root.<String>get("createdAt"), since));
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

