package com.revytechinc.honchoinspector.auth.repo;

import com.revytechinc.honchoinspector.auth.entity.AuditLogEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable JPA {@link Specification} factories for {@link AuditLogEntity}.
 * Each method is a no-op when the matching filter argument is null
 * or blank — that's the convention used across the dashboard so a
 * single search() call can take a fully- or partially-populated
 * filter set without conditional branches at the call site.
 */
public final class AuditLogSpecifications {

    private AuditLogSpecifications() {}

    public static Specification<AuditLogEntity> actionEquals(String action) {
        if (action == null || action.isBlank()) return null;
        return (root, cq, cb) -> cb.equal(root.get("action"), action);
    }

    public static Specification<AuditLogEntity> actorEquals(String actor) {
        if (actor == null || actor.isBlank()) return null;
        return (root, cq, cb) -> cb.equal(root.get("actorUserId"), actor);
    }

    public static Specification<AuditLogEntity> targetEquals(String target) {
        if (target == null || target.isBlank()) return null;
        return (root, cq, cb) -> cb.equal(root.get("targetUserId"), target);
    }

    public static Specification<AuditLogEntity> createdAtOrAfter(java.time.Instant since) {
        if (since == null) return null;
        String iso = since.toString();
        return (root, cq, cb) -> cb.greaterThanOrEqualTo(
            root.<String>get("createdAt"), iso);
    }

    /**
     * Combine the four optional filters into a single Specification.
     * A null Specification is treated as "no filter" by Spring Data.
     */
    public static Specification<AuditLogEntity> all(
        String action, String actor, String target, java.time.Instant since
    ) {
        List<Specification<AuditLogEntity>> specs = new ArrayList<>(4);
        Specification<AuditLogEntity> s;
        if ((s = actionEquals(action)) != null) specs.add(s);
        if ((s = actorEquals(actor)) != null) specs.add(s);
        if ((s = targetEquals(target)) != null) specs.add(s);
        if ((s = createdAtOrAfter(since)) != null) specs.add(s);
        if (specs.isEmpty()) return null;
        return (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>(specs.size());
            for (var spec : specs) {
                ps.add(spec.toPredicate(root, cq, cb));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }
}
