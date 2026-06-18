package com.revytechinc.honchoinspector.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class ProfileDao {

    private final JdbcTemplate jdbc;

    public ProfileDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ISO-8601 TEXT for timestamps; see UserDao.ROW comment.
    private static final RowMapper<Profile> ROW = (rs, n) -> new Profile(
        rs.getString("id"),
        rs.getString("user_id"),
        rs.getString("label"),
        rs.getString("api_key_encrypted"),
        rs.getString("base_url"),
        rs.getString("workspace_id"),
        rs.getString("honcho_user_name"),
        Instant.parse(rs.getString("created_at")),
        Instant.parse(rs.getString("updated_at")),
        rs.getString("api_version")
    );

    public Profile findById(String id) {
        return jdbc.query("SELECT * FROM honcho_profiles WHERE id = ?", ROW, id)
            .stream().findFirst().orElse(null);
    }

    public List<Profile> findByUserId(String userId) {
        return jdbc.query(
            "SELECT * FROM honcho_profiles WHERE user_id = ? ORDER BY label",
            ROW, userId
        );
    }

    public void insert(Profile p) {
        jdbc.update(
            "INSERT INTO honcho_profiles (id, user_id, label, api_key_encrypted, base_url, workspace_id, honcho_user_name, created_at, updated_at, api_version) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            p.id(), p.userId(), p.label(), p.apiKeyEncrypted(),
            p.baseUrl(), p.workspaceId(), p.honchoUserName(),
            p.createdAt().toString(), p.updatedAt().toString(),
            p.apiVersion()
        );
    }

    public void update(Profile p) {
        jdbc.update(
            "UPDATE honcho_profiles SET label = ?, api_key_encrypted = ?, base_url = ?, workspace_id = ?, honcho_user_name = ?, updated_at = ?, api_version = ? "
                + "WHERE id = ?",
            p.label(), p.apiKeyEncrypted(), p.baseUrl(), p.workspaceId(), p.honchoUserName(),
            p.updatedAt().toString(), p.apiVersion(), p.id()
        );
    }

    public void deleteById(String id) {
        jdbc.update("DELETE FROM honcho_profiles WHERE id = ?", id);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM honcho_profiles", Long.class);
        return n == null ? 0 : n;
    }
}
