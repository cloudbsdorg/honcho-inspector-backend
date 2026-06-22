package com.revytechinc.honchoinspector.auth;

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

    private static final RowMapper<User> ROW = (rs, n) -> new User(
        rs.getString("id"),
        rs.getString("username"),
        rs.getString("password_hash"),
        rs.getString("firstname"),
        rs.getString("lastname"),
        rs.getString("email"),
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

    public long countByIsAdmin(boolean isAdmin) {
        Long n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE is_admin = ?", Long.class,
            isAdmin ? 1 : 0);
        return n == null ? 0 : n;
    }

    public List<User> findAll() {
        return jdbc.query("SELECT * FROM users ORDER BY created_at", ROW);
    }

    public List<User> listPaginated(int limit, int offset) {
        return jdbc.query(
            "SELECT * FROM users ORDER BY created_at LIMIT ? OFFSET ?",
            ROW, limit, offset);
    }

    public long countSince(Instant since) {
        Long n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE created_at >= ?", Long.class,
            since.toString());
        return n == null ? 0 : n;
    }

    public List<User> searchByQuery(String query, int limit, int offset) {
        if (query == null || query.isBlank()) {
            return listPaginated(limit, offset);
        }
        var like = "%" + query.toLowerCase() + "%";
        return jdbc.query(
            "SELECT * FROM users WHERE "
          + "LOWER(username) LIKE ? OR "
          + "LOWER(COALESCE(firstname,'')) LIKE ? OR "
          + "LOWER(COALESCE(lastname,'')) LIKE ? OR "
          + "LOWER(COALESCE(email,'')) LIKE ? "
          + "ORDER BY created_at LIMIT ? OFFSET ?",
            ROW, like, like, like, like, limit, offset);
    }

    public long countSearchByQuery(String query) {
        if (query == null || query.isBlank()) {
            return count();
        }
        var like = "%" + query.toLowerCase() + "%";
        Long n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE "
          + "LOWER(username) LIKE ? OR "
          + "LOWER(COALESCE(firstname,'')) LIKE ? OR "
          + "LOWER(COALESCE(lastname,'')) LIKE ? OR "
          + "LOWER(COALESCE(email,'')) LIKE ?",
            Long.class, like, like, like, like);
        return n == null ? 0 : n;
    }

    public void insert(User user) {
        jdbc.update(
            "INSERT INTO users (id, username, password_hash, firstname, lastname, email, is_admin, created_at) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            user.id(),
            user.username(),
            user.passwordHash(),
            user.firstname(),
            user.lastname(),
            user.email(),
            user.isAdmin() ? 1 : 0,
            user.createdAt().toString()
        );
    }

    public void updateIdentity(String id, String username, String firstname, String lastname, String email) {
        jdbc.update(
            "UPDATE users SET username = ?, firstname = ?, lastname = ?, email = ? WHERE id = ?",
            username, firstname, lastname, email, id);
    }

    public void updateAdmin(String id, boolean isAdmin) {
        jdbc.update("UPDATE users SET is_admin = ? WHERE id = ?", isAdmin ? 1 : 0, id);
    }

    public void updatePasswordHash(String id, String passwordHash) {
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordHash, id);
    }

    public void deleteById(String id) {
        jdbc.update("DELETE FROM users WHERE id = ?", id);
    }
}
