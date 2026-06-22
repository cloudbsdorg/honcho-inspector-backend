-- honcho-inspector schema (v1)
-- Idempotent: safe to re-run on every startup.
-- Timestamps are stored as ISO-8601 TEXT (Instant.toString format) so the
-- sqlite-jdbc driver can round-trip them with setString/getString.

CREATE TABLE IF NOT EXISTS users (
    id            TEXT    PRIMARY KEY,
    username      TEXT    UNIQUE NOT NULL,
    password_hash TEXT    NOT NULL,
    firstname     TEXT,
    lastname      TEXT,
    email         TEXT,
    is_admin      INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email) WHERE email IS NOT NULL;

CREATE TABLE IF NOT EXISTS honcho_profiles (
    id                TEXT    PRIMARY KEY,
    user_id           TEXT    NOT NULL,
    label             TEXT    NOT NULL,
    api_key_encrypted TEXT    NOT NULL,
    base_url          TEXT    NOT NULL,
    workspace_id      TEXT    NOT NULL,
    honcho_user_name  TEXT    NOT NULL,
    -- api_version: nullable so existing rows pre-migration are valid; new rows
    -- may set it via ProfileService. Migrated to a real column by
    -- SchemaMigrator for DBs created before this column existed.
    api_version       TEXT,
    created_at        TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at        TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE (user_id, label)
);

CREATE TABLE IF NOT EXISTS auth_sessions (
    id           TEXT    PRIMARY KEY,
    user_id      TEXT    NOT NULL,
    created_at   TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    last_seen_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    expires_at   TEXT,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_profiles_user  ON honcho_profiles(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_user  ON auth_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_expiry ON auth_sessions(expires_at);

CREATE TABLE IF NOT EXISTS audit_log (
    id              TEXT    PRIMARY KEY,
    actor_user_id   TEXT,
    action          TEXT    NOT NULL,
    target_user_id  TEXT,
    target_resource TEXT,
    ip              TEXT,
    session_id      TEXT,
    metadata        TEXT,
    created_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    FOREIGN KEY (actor_user_id)  REFERENCES users(id) ON DELETE SET NULL,
    FOREIGN KEY (target_user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_actor   ON audit_log(actor_user_id);
CREATE INDEX IF NOT EXISTS idx_audit_target  ON audit_log(target_user_id);
CREATE INDEX IF NOT EXISTS idx_audit_action  ON audit_log(action);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log(created_at);
