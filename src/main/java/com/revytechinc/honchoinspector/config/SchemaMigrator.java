package com.revytechinc.honchoinspector.config;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/**
 * Lightweight idempotent schema migrator. Runs once at application startup
 * (after Spring Boot's {@code spring.sql.init} has created or upgraded the
 * schema via {@code schema.sql}) and applies any column-level changes that
 * are too invasive for a pure {@code CREATE TABLE IF NOT EXISTS} block.
 *
 * <p>Currently the only such change is adding {@code honcho_profiles.api_version}
 * for DBs created before that column existed in the bundled schema. SQLite
 * has no {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS}, so we look up
 * {@code PRAGMA table_info(honcho_profiles)} and only issue the {@code ALTER}
 * when the column is absent.
 */
@Component
public class SchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private static final String TARGET_TABLE = "honcho_profiles";
    private static final String TARGET_COLUMN = "api_version";
    private static final String ADD_COLUMN_SQL =
        "ALTER TABLE " + TARGET_TABLE + " ADD COLUMN " + TARGET_COLUMN + " TEXT";

    private final JdbcTemplate jdbc;

    public SchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        if (hasColumn(TARGET_COLUMN)) {
            log.debug("SchemaMigrator: {}.{} already present, nothing to do", TARGET_TABLE, TARGET_COLUMN);
            return;
        }
        log.info("SchemaMigrator: adding column {}.{} to existing database", TARGET_TABLE, TARGET_COLUMN);
        jdbc.execute(ADD_COLUMN_SQL);
        log.info("SchemaMigrator: column {}.{} added", TARGET_TABLE, TARGET_COLUMN);
    }

    private boolean hasColumn(String columnName) {
        List<Map<String, Object>> rows = jdbc.queryForList("PRAGMA table_info(" + TARGET_TABLE + ")");
        for (Map<String, Object> row : rows) {
            Object name = row.get("name");
            if (name != null && columnName.equalsIgnoreCase(name.toString())) {
                return true;
            }
        }
        return false;
    }
}
