package com.revytechinc.honchoinspector.auth.repo;

import com.revytechinc.honchoinspector.auth.entity.AuthSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AuthSessionRepository extends JpaRepository<AuthSessionEntity, String> {

    List<AuthSessionEntity> findByUserId(String userId);

    long countByUserId(String userId);

    /**
     * Update the last-seen timestamp for one session. The native
     * statement is a single UPDATE; we use the standard
     * {@code save(entity)} path so the change is written via the
     * entity manager's dirty-tracking rather than a JPA bulk update.
     */
    default void touchLastSeen(String id, Instant ts) {
        findById(id).ifPresent(s -> {
            s.setLastSeenAt(ts);
            save(s);
        });
    }

    /**
     * Delete every session for one user. Standard Spring Data derived
     * method.
     */
    long deleteByUserId(String userId);

    /**
     * Delete all expired sessions (expiresAt < now, and the column
     * is set — not null). Pure Java filter over findAll because the
     * table is small enough that a single bulk delete-then-trim is
     * not worth a custom @Query.
     */
    default int deleteExpired(Instant now) {
        int n = 0;
        for (var s : findAll()) {
            if (s.getExpiresAtAsInstant() != null
                && s.getExpiresAtAsInstant().isBefore(now)) {
                deleteById(s.getId());
                n++;
            }
        }
        return n;
    }
}
