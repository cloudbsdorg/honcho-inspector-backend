package com.revytechinc.honchoinspector.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Verifies that {@link SchemaMigrator} is idempotent and that it adds
 * {@code honcho_profiles.api_version} to a pre-migration SQLite database.
 * Uses an in-memory SQLite, no Spring context.
 */
class SchemaMigratorTest {

    private static final String PRE_MIGRATION_SCHEMA = """
        CREATE TABLE honcho_profiles (
            id                TEXT PRIMARY KEY,
            user_id           TEXT NOT NULL,
            label             TEXT NOT NULL,
            api_key_encrypted TEXT NOT NULL,
            base_url          TEXT NOT NULL,
            workspace_id      TEXT NOT NULL,
            honcho_user_name  TEXT NOT NULL,
            created_at        TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
            updated_at        TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
        );
        """;

    private static final String POST_MIGRATION_SCHEMA = """
        CREATE TABLE honcho_profiles (
            id                TEXT PRIMARY KEY,
            user_id           TEXT NOT NULL,
            label             TEXT NOT NULL,
            api_key_encrypted TEXT NOT NULL,
            base_url          TEXT NOT NULL,
            workspace_id      TEXT NOT NULL,
            honcho_user_name  TEXT NOT NULL,
            api_version       TEXT,
            created_at        TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
            updated_at        TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
        );
        """;

    @Test
    void addsApiVersionColumnWhenMissing() throws Exception {
        try (SingleConnectionDataSource ds = newInMemoryDataSource()) {
            applySchema(ds.getConnection(), PRE_MIGRATION_SCHEMA);
            assertThat(columnNames(ds.getConnection(), "honcho_profiles"))
                .doesNotContain("api_version");

            new SchemaMigrator(new JdbcTemplate(ds)).migrate();

            assertThat(columnNames(ds.getConnection(), "honcho_profiles"))
                .contains("api_version");
        }
    }

    @Test
    void isIdempotentWhenColumnAlreadyPresent() throws Exception {
        try (SingleConnectionDataSource ds = newInMemoryDataSource()) {
            applySchema(ds.getConnection(), POST_MIGRATION_SCHEMA);
            assertThat(columnNames(ds.getConnection(), "honcho_profiles"))
                .contains("api_version");

            new SchemaMigrator(new JdbcTemplate(ds)).migrate();

            assertThat(columnNames(ds.getConnection(), "honcho_profiles"))
                .contains("api_version");
        }
    }

    @Test
    void canRunMultipleTimesInARowOnPreMigrationDb() throws Exception {
        try (SingleConnectionDataSource ds = newInMemoryDataSource()) {
            applySchema(ds.getConnection(), PRE_MIGRATION_SCHEMA);
            var migrator = new SchemaMigrator(new JdbcTemplate(ds));

            migrator.migrate();
            migrator.migrate();
            migrator.migrate();

            assertThat(columnNames(ds.getConnection(), "honcho_profiles"))
                .contains("api_version");
        }
    }

    private static SingleConnectionDataSource newInMemoryDataSource() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        conn.setAutoCommit(true);
        return new SingleConnectionDataSource(conn, true);
    }

    private static void applySchema(Connection conn, String schema) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(schema);
        }
    }

    private static List<String> columnNames(Connection conn, String table) throws SQLException {
        var names = new ArrayList<String>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        }
        return names;
    }
}
