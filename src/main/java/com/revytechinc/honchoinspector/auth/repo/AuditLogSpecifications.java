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

    public static Specification<AuditLogEntity> targetResourceEquals(String resource) {
        if (resource == null || resource.isBlank()) return null;
        return (root, cq, cb) -> cb.equal(root.get("targetResource"), resource);
    }

    public static Specification<AuditLogEntity> targetResourceStartsWith(String resourceType) {
        if (resourceType == null || resourceType.isBlank()) return null;
        // Match the prefix `{resourceType}:` so that `?resource=conclusion`
        // matches `conclusion:abc123` but NOT `conclusion_extra:xyz` (the
        // latter is not a real prefix in our audit vocabulary, but the
        // guard preserves the explicit separator discipline used when
        // the controller writes `peer_card:<id>` / `conclusion:<id>`).
        String prefix = resourceType.endsWith(":") ? resourceType : resourceType + ":";
        return (root, cq, cb) -> cb.like(root.get("targetResource"), prefix + "%");
    }

    public static Specification<AuditLogEntity> createdAtOrAfter(java.time.Instant since) {
        if (since == null) return null;
        String iso = since.toString();
        return (root, cq, cb) -> cb.greaterThanOrEqualTo(
            root.<String>get("createdAt"), iso);
    }

    /**
     * Combine the four legacy optional filters into a single Specification.
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

    /**
     * Combine the six optional filters into a single Specification. The
     * {@code resourceType} (e.g. {@code "conclusion"}) matches any row
     * whose {@code target_resource} starts with {@code <type>:}; the
     * {@code resourceId} (e.g. {@code "abc123"}) is matched exactly when
     * both are supplied (combined with the prefix constraint above).
     *
     * <p>If only {@code resourceId} is supplied (no {@code resourceType})
     * it is matched as an exact equality on {@code target_resource} —
     * useful for operators who already know the full resource string.
     */
    public static Specification<AuditLogEntity> all(
        String action,
        String actor,
        String target,
        String resourceType,
        String resourceId,
        java.time.Instant since
    ) {
        List<Specification<AuditLogEntity>> specs = new ArrayList<>(6);
        Specification<AuditLogEntity> s;
        if ((s = actionEquals(action)) != null) specs.add(s);
        if ((s = actorEquals(actor)) != null) specs.add(s);
        if ((s = targetEquals(target)) != null) specs.add(s);
        // When both are supplied, this gives us "exact resource match"
        // (e.g. ?resource=conclusion&id=abc -> target_resource = "conclusion:abc").
        if (resourceType != null && !resourceType.isBlank()
            && resourceId != null && !resourceId.isBlank()) {
            String fullId = resourceType.endsWith(":") ? resourceType + resourceId : resourceType + ":" + resourceId;
            specs.add(targetResourceEquals(fullId));
        } else if (resourceType != null && !resourceType.isBlank()) {
            specs.add(targetResourceStartsWith(resourceType));
        } else if (resourceId != null && !resourceId.isBlank()) {
            specs.add(targetResourceEquals(resourceId));
        }
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
