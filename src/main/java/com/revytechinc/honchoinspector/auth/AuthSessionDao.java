package com.revytechinc.honchoinspector.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class AuthSessionDao {

    private final JdbcTemplate jdbc;

    public AuthSessionDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ISO-8601 TEXT for timestamps; see UserDao.ROW comment.
    private static final RowMapper<AuthSession> ROW = (rs, n) -> new AuthSession(
        rs.getString("id"),
        rs.getString("user_id"),
        Instant.parse(rs.getString("created_at")),
        Instant.parse(rs.getString("last_seen_at")),
        Optional.ofNullable(rs.getString("expires_at")).map(Instant::parse)
    );

    public Optional<AuthSession> findById(String id) {
        return jdbc.query("SELECT * FROM auth_sessions WHERE id = ?", ROW, id)
            .stream().findFirst();
    }

    public void insert(AuthSession session) {
        jdbc.update(
            "INSERT INTO auth_sessions (id, user_id, created_at, last_seen_at, expires_at) VALUES (?, ?, ?, ?, ?)",
            session.id(),
            session.userId(),
            session.createdAt().toString(),
            session.lastSeenAt().toString(),
            session.expiresAt().map(Instant::toString).orElse(null)
        );
    }

    public void touch(String id, Instant now) {
        jdbc.update("UPDATE auth_sessions SET last_seen_at = ? WHERE id = ?", now.toString(), id);
    }

    public void deleteById(String id) {
        jdbc.update("DELETE FROM auth_sessions WHERE id = ?", id);
    }

    public int deleteExpired(Instant now) {
        return jdbc.update("DELETE FROM auth_sessions WHERE expires_at IS NOT NULL AND expires_at < ?", now.toString());
    }

    public List<AuthSession> findByUserId(String userId) {
        return jdbc.query(
            "SELECT * FROM auth_sessions WHERE user_id = ? ORDER BY last_seen_at DESC",
            ROW, userId);
    }

    public int deleteByUserId(String userId) {
        return jdbc.update("DELETE FROM auth_sessions WHERE user_id = ?", userId);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions", Long.class);
        return n == null ? 0 : n;
    }
}
