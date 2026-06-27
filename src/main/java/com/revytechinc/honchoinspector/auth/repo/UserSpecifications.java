package com.revytechinc.honchoinspector.auth.repo;

import com.revytechinc.honchoinspector.auth.entity.UserEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable JPA {@link Specification} factories for {@link UserEntity}.
 * Kept here (in the same package as the repository) so the service
 * layer can call into them without depending on the {@code @Entity}
 * class directly. The methods are static + side-effect-free.
 */
public final class UserSpecifications {

    private UserSpecifications() {}

    /**
     * Build a "user search" specification that matches users where the
     * (lowercased) query string is contained in ANY of username,
     * firstname, lastname, email. Used by the admin user-search
     * endpoint and the AdminUserService.
     */
    public static Specification<UserEntity> matchesQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String pattern = "%" + query.toLowerCase() + "%";
        return (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.like(cb.lower(root.get("username")), pattern));
            ps.add(cb.like(cb.lower(cb.coalesce(root.get("firstname"), "")), pattern));
            ps.add(cb.like(cb.lower(cb.coalesce(root.get("lastname"), "")), pattern));
            ps.add(cb.like(cb.lower(cb.coalesce(root.get("email"), "")), pattern));
            return cb.or(ps.toArray(new Predicate[0]));
        };
    }

    /**
     * "user was created at or after this instant" filter. Passing
     * {@code null} disables the filter (matches everyone).
     */
    public static Specification<UserEntity> createdAtOrAfter(java.time.Instant since) {
        if (since == null) {
            return null;
        }
        String iso = since.toString();
        return (root, cq, cb) -> cb.greaterThanOrEqualTo(
            root.<String>get("createdAt"), iso);
    }
}
