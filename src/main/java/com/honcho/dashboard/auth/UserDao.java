package com.honcho.dashboard.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class UserDao {

    private final JdbcTemplate jdbc;

    public UserDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Timestamps are written as ISO-8601 TEXT (Instant.toString) and read back
    // with Instant.parse. Bypasses the xerial sqlite-jdbc driver's broken
    // Timestamp handling, which stores Timestamp as Long.toString(getTime()).
    // The schema DEFAULT uses strftime(...,'now') which already produces ISO-8601.
    private static final RowMapper<User> ROW = (rs, n) -> new User(
        rs.getString("id"),
        rs.getString("username"),
        rs.getString("password_hash"),
        rs.getInt("is_admin") != 0,
        Instant.parse(rs.getString("created_at"))
    );

    public Optional<User> findById(String id) {
        return jdbc.query("SELECT * FROM users WHERE id = ?", ROW, id)
            .stream().findFirst();
    }

    public Optional<User> findByUsername(String username) {
        return jdbc.query("SELECT * FROM users WHERE username = ?", ROW, username)
            .stream().findFirst();
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return n == null ? 0 : n;
    }

    public List<User> findAll() {
        return jdbc.query("SELECT * FROM users ORDER BY created_at", ROW);
    }

    public void insert(User user) {
        jdbc.update(
            "INSERT INTO users (id, username, password_hash, is_admin, created_at) VALUES (?, ?, ?, ?, ?)",
            user.id(),
            user.username(),
            user.passwordHash(),
            user.isAdmin() ? 1 : 0,
            user.createdAt().toString()
        );
    }
}
