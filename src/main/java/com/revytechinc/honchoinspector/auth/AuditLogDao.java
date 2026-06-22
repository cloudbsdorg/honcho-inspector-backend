package com.revytechinc.honchoinspector.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class AuditLogDao {

    private final JdbcTemplate jdbc;

    public AuditLogDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Entry e) {
        jdbc.update(
            "INSERT INTO audit_log (id, actor_user_id, action, target_user_id, target_resource, ip, session_id, metadata, created_at) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            e.id(), e.actorUserId(), e.action(), e.targetUserId(),
            e.targetResource(), e.ip(), e.sessionId(), e.metadata(),
            e.createdAt().toString());
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class);
        return n == null ? 0 : n;
    }

    public long countOlderThan(Instant cutoff) {
        Long n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE created_at < ?",
            Long.class, cutoff.toString());
        return n == null ? 0 : n;
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbc.update(
            "DELETE FROM audit_log WHERE created_at < ?",
            cutoff.toString());
    }

    public int deleteOldestKeeping(long keep) {
        long total = count();
        if (total <= keep) return 0;
        long toDelete = total - keep;
        return jdbc.update(
            "DELETE FROM audit_log WHERE id IN ("
          + "SELECT id FROM audit_log ORDER BY created_at ASC LIMIT ?)",
            toDelete);
    }

    public List<Entry> search(Query q, int limit, int offset) {
        var sql = new StringBuilder(
            "SELECT * FROM audit_log WHERE 1=1");
        var args = new java.util.ArrayList<>();
        if (q.actor != null && !q.actor.isBlank()) {
            sql.append(" AND actor_user_id = ?");
            args.add(q.actor);
        }
        if (q.target != null && !q.target.isBlank()) {
            sql.append(" AND target_user_id = ?");
            args.add(q.target);
        }
        if (q.action != null && !q.action.isBlank()) {
            sql.append(" AND action = ?");
            args.add(q.action);
        }
        if (q.since != null) {
            sql.append(" AND created_at >= ?");
            args.add(q.since.toString());
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(),
            (rs, n) -> new Entry(
                rs.getString("id"),
                rs.getString("actor_user_id"),
                rs.getString("action"),
                rs.getString("target_user_id"),
                rs.getString("target_resource"),
                rs.getString("ip"),
                rs.getString("session_id"),
                rs.getString("metadata"),
                Instant.parse(rs.getString("created_at"))),
            args.toArray());
    }

    public record Query(String actor, String target, String action, Instant since) {}

    public record Entry(
        String id,
        String actorUserId,
        String action,
        String targetUserId,
        String targetResource,
        String ip,
        String sessionId,
        String metadata,
        Instant createdAt
    ) {}
}
