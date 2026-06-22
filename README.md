# honcho-inspector-backend

[![CI](https://github.com/cloudbsdorg/honcho-inspector-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/cloudbsdorg/honcho-inspector-backend/actions/workflows/ci.yml)

Java 25 / Spring Boot 3.5.0 backend for [honcho-inspector](../honcho-inspector-ui).

Sits between the Angular UI and one or more Honcho instances. Keeps the Honcho API key off the browser, externalizes config per-OS convention, and serves a multi-user multi-profile admin surface (one SQLite DB, no external service to run).

License: **BSD 3-Clause**. See [LICENSE](LICENSE).

Part of the **honcho-inspector** product, split across two repos:

| Repo | Purpose |
|---|---|
| [honcho-inspector-backend](https://github.com/cloudbsdorg/honcho-inspector-backend) (this) | Spring Boot 3.5 + Java 25, SQLite, port 8080 |
| [honcho-inspector-ui](https://github.com/cloudbsdorg/honcho-inspector-ui) | Angular 22 view-only dashboard, dev port 4200 |

## Stack

- **Java 25** (records, pattern matching, virtual threads)
- **Spring Boot 3.5.0** — web, jdbc, validation, security-crypto
- **SQLite 3** via xerial jdbc 3.46.1.3 (embedded, file-based; no daemon)
- **Maven** build
- **No `@Autowired` anywhere** — constructor injection only
- **No Spring Security** — the `SessionAuthFilter` is a 50-line `OncePerRequestFilter`

## Quick start

### Dev (in-memory, no files)

```bash
mvn spring-boot:run
```

The bundled `application.yml` uses an OS-aware default config dir. In dev with no `HONCHO_CONFIG_DIR` set, the DB will land at `./honcho-inspector.db` in the working directory.

### Dev with explicit in-memory DB (test mode)

```bash
HONCHO_DB_PATH=jdbc:sqlite::memory: \
HONCHO_CRYPTO_KEY="$(openssl rand -base64 32)" \
mvn spring-boot:run
```

In-memory is useful for quick tests, but state is lost on restart.

### Prod (OS-aware config dir)

Pick the dir for your OS, then run via the launcher script:

| OS      | Config dir                                          | Launcher reads env |
| ------- | --------------------------------------------------- | ------------------ |
| Linux   | `/etc/honcho-inspector/`                            | `HONCHO_CONFIG_DIR` |
| FreeBSD | `/usr/local/etc/honcho-inspector/`                  | `HONCHO_CONFIG_DIR` |
| macOS   | `~/Library/Application Support/honcho-inspector/`   | `HONCHO_CONFIG_DIR` |
| Windows | `%APPDATA%\honcho-inspector\`                       | `HONCHO_CONFIG_DIR` |

```bash
# Linux example
sudo mkdir -p /etc/honcho-inspector
sudo cp etc/honcho-inspector/application.yml.example /etc/honcho-inspector/application.yml
sudo $EDITOR /etc/honcho-inspector/application.yml       # set HONCHO_CRYPTO_KEY, CORS_ALLOWED_ORIGINS, etc.
sudo bin/honcho-inspector                                # OS detection sets HONCHO_CONFIG_DIR automatically
```

On first run, the SQLite DB is created at `$HONCHO_CONFIG_DIR/honcho-inspector.db` and the schema is loaded from `src/main/resources/schema.sql` (idempotent — `CREATE TABLE IF NOT EXISTS`).

On a fresh DB with **no users**, `AdminBootstrap` reads `honcho.bootstrap.*` from `/etc/honcho-inspector/application.yml` and creates the first admin if both `admin-username` and `admin-password` are set. The bundled jar ships the bootstrap block blank; production opt-in is by populating the drop-in config. See [Admin onboarding](#admin-onboarding) below.

## Configuration

All config is externalized. Priority (high → low):

1. CLI flag: `bin/honcho-inspector -- --server.port=9090`
2. Environment variable (Spring relaxed binding)
3. Drop-in file at `<config-dir>/application.yml` (loaded additively)
4. Bundled `src/main/resources/application.yml` in the jar

### Environment variables

| Var                            | Default                                | Notes |
| ------------------------------ | -------------------------------------- | ----- |
| `PORT`                         | `8080`                                 | Spring `server.port` |
| `CORS_ALLOWED_ORIGINS`         | `http://localhost:4200,http://127.0.0.1:4200` | Comma-separated; allows the dev UI origin |
| `HONCHO_BASE_URL`              | `https://api.honcho.dev`               | Default Honcho upstream; per-profile override on `Profile` |
| `HONCHO_API_VERSION`           | `v3`                                   | Path prefix for `/v3/...` |
| `HONCHO_REQUEST_TIMEOUT_MS`    | `30000`                                | RestClient timeout |
| `HONCHO_CONFIG_DIR`            | OS-aware (see table above)             | Where the drop-in `application.yml` and DB live |
| `HONCHO_DB_PATH`               | `jdbc:sqlite:${HONCHO_DB_FILE:honcho-inspector.db}` | Override the JDBC URL; e.g. `:memory:` for tests |
| `HONCHO_DB_FILE`               | `honcho-inspector.db`                  | Just the filename; resolved against `HONCHO_CONFIG_DIR` |
| `HONCHO_CRYPTO_KEY`            | (random, ephemeral — values lost on restart) | Base64 32 bytes; encrypts profile API keys at rest |
| `SESSION_TTL_MINUTES`          | `0` (never expire)                     | Set non-zero to expire idle sessions |
| `HONCHO_LOG_LEVEL`             | `INFO`                                 | Root + our packages; `org.springframework.web` logs at the same level |
| `HONCHO_LOG_MAX_FILE_SIZE`     | `100MB`                                | Active JSONL file cap before mid-day roll (`SizeAndTimeBasedRollingPolicy`) |
| `HONCHO_LOG_MAX_HISTORY`       | `30`                                   | Number of days of rotated `.jsonl.gz` files kept |
| `HONCHO_LOG_TOTAL_SIZE_CAP`    | `500MB`                                | Total disk cap across active + rotated files; oldest pruned first |
| `HONCHO_BOOTSTRAP_ADMIN_USERNAME` | (blank — no admin created)         | First-admin username, read by `AdminBootstrap` on startup if the DB is empty |
| `HONCHO_BOOTSTRAP_ADMIN_PASSWORD` | (blank — no admin created)         | First-admin password; min 8 chars. Set via secret manager, not in `/etc/...yml` |
| `HONCHO_BOOTSTRAP_ADMIN_FIRSTNAME` | (blank)                            | Optional first name for the bootstrap admin user record |
| `HONCHO_BOOTSTRAP_ADMIN_LASTNAME`  | (blank)                            | Optional last name |
| `HONCHO_BOOTSTRAP_ADMIN_EMAIL`     | (blank)                            | Optional email (must be unique if set) |
| `HONCHO_AUDIT_RETENTION_DAYS`   | `90`                                   | Age cap for `audit_log` rows; rows older than this are purged daily |
| `HONCHO_AUDIT_MAX_ROWS`         | `1000000`                              | Size cap; if `COUNT(*) > max-rows`, oldest rows are deleted until the cap is satisfied |
| `HONCHO_AUDIT_PURGE_CRON`       | `0 0 3 * * *`                          | Cron for the retention sweep (3:00 AM local). Manual trigger via `POST /api/admin/maintenance/audit/purge` |

Logs are emitted as **structured JSONL** (one JSON event per line) to
`$HONCHO_CONFIG_DIR/logs/honcho-inspector.jsonl` and stdout, with API
keys / Bearer tokens / passwords scrubbed at the encoder. Full policy,
schema, sizing math, and `jq` / `gunzip` one-liners are in
[`docs/logging.md`](docs/logging.md).

Generate a strong crypto key:
```bash
openssl rand -base64 32
```

### Drop-in config example

`/etc/honcho-inspector/application.yml` (Linux):
```yaml
server:
  port: 9090
cors:
  allowed-origins: https://inspector.example.com
honcho:
  base-url: https://mcp.honcho.cloudbsd.org
  api-version: v3
session:
  ttl-minutes: 60
```

## API surface

| Path prefix | Auth | Purpose |
|---|---|---|
| `/api/health` | public | Liveness probe (returns `ok` + `needs_register`; no user/session/profile counts) |
| `/api/auth/login` | public | Exchange username/password for a session id |
| `/api/auth/me`, `/api/auth/logout` | session | Current user, invalidate session |
| `/api/auth/register` | **admin-only** | Create a new user. Not public. See [Admin onboarding](#admin-onboarding). |
| `/api/profiles/*` | session | Per-user Honcho profiles (CRUD, reveal, test) |
| `/api/{peers,sessions,queue-status,workspace,search,dream,orgs,stats,reports,invites}/*` | session + profile header | Honcho proxy (forwards to the selected profile's upstream) |
| `/api/admin/users/*`, `/api/admin/audit`, `/api/admin/dashboard/*`, `/api/admin/maintenance/*` | **admin-only** | Admin management surface. See [Admin API](#admin-api) below. |

OpenAPI spec: see [docs/openapi.yaml](docs/openapi.yaml) and Swagger UI at [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html).
The hand-written narrative spec is at `docs/openapi.yaml`; the live springdoc snapshot is at `docs/openapi.generated.json`. The [drift check](docs/regenerating-openapi.md) enforces they stay aligned.

### Auth flow

1. (First-time setup) An admin is created either by `AdminBootstrap` reading `honcho.bootstrap.*` from the drop-in config, or by an existing admin via `POST /api/admin/users`. There is **no public registration**; the old "first user to register becomes admin" path is gone.
2. `POST /api/auth/login` with `{ username, password }` — returns `200 { sessionId, user: { ... } }`. Store `sessionId`.
3. Send `X-Session-Id: <sessionId>` on every subsequent request.
4. `GET /api/auth/me` — re-fetch the current user (useful on app reload).
5. `POST /api/auth/logout` — invalidates the session server-side.

### Profile flow

A user can have many Honcho profiles (different workspaces, different API keys, different base URLs).

1. `GET /api/profiles` — list the current user's profiles.
2. `POST /api/profiles` with `{ label, apiKey, baseUrl, workspaceId, honchoUserName }` — create. API key is AES-256-GCM encrypted at rest.
3. Pick a profile: send `X-Honcho-Profile-Id: <profileId>` on every Honcho proxy call. Missing header → `400`. Wrong user → `404`.
4. `GET /api/profiles/{id}/reveal` — decrypt and return the plaintext API key (use only when you need to display it).
5. `POST /api/profiles/{id}/test` — try a `GET /v3/workspaces/{workspaceId}` against the profile's base URL + API key.

### Honcho proxy

Anything under `/api/peers/*`, `/api/sessions/*`, `/api/queue-status`, `/api/workspace/info`, `/api/search`, `/api/dream` is forwarded to Honcho. The `HonchoProxyService` adds `Authorization: Bearer <apiKey>` and `X-Honcho-User-Name: <userName>` per-request from the selected profile, and strips a trailing `/mcp` from the base URL if present.

`X-Session-Id` is the row's session, `X-Honcho-Profile-Id` is which Honcho instance to talk to. These are intentionally separate so a single user can be logged into multiple Honcho workspaces and switch between them.

### Honcho provider layer

The proxy above is version-agnostic on purpose: a per-version `HonchoClient` (e.g. `HonchoV3Client`) is selected by a `HonchoClientFactory` that indexes each registered client by the `HonchoApiVersion` it advertises. A `HonchoProviderRegistry` then dispatches each `HonchoOperation` to the right `HonchoProvider` `@Component` for that version — one provider per resource cluster, not one per endpoint. Per-profile version overrides live in the `honcho_profiles.api_version` column: NULL inherits the `honcho.api-version` default; non-NULL pins that profile to a specific Honcho version so one operator can run a workspace on v3 today and start migrating another to v4 without redeploying.

For the full anatomy + custom-provider tutorial + V4 walkthrough, see [docs/honcho-providers.md](docs/honcho-providers.md).

### Admin onboarding

There is **no public sign-up**. The first admin is created by `AdminBootstrap` on a fresh DB by reading `honcho.bootstrap.*` from `/etc/honcho-inspector/application.yml` (see the etc template). If the bundled jar is started on a fresh DB and the bootstrap block is blank, `AdminBootstrap` logs a warning and does nothing — the operator must populate the config and restart, or insert a user directly via `sqlite3` and flip `is_admin = 1`.

After the first admin exists, all subsequent users are created by an admin via `POST /api/admin/users` (admin-only). The `honcho.bootstrap.*` credentials should be removed from the config file once the first admin exists — leaving them in is a credential-on-disk leak.

Recovery from a lost-admin situation: edit `/etc/honcho-inspector/application.yml` and re-populate `honcho.bootstrap.admin-username` and `honcho.bootstrap.admin-password`. On the next start, `AdminBootstrap` re-checks `users.count() == 0` and is a no-op (the lost admin's row is still there). For a true lockout, set `is_admin = 1` on an existing user via `sqlite3` directly.

### Admin API

All paths under `/api/admin/**` are gated by `@RequireAdmin` + the `AdminAuthInterceptor` HandlerInterceptor. The interceptor runs **after** `SessionAuthFilter`, so unauthenticated callers get 401 before the admin check, and authenticated non-admins get 403 `admin only`.

| Method | Path | Purpose |
|---|---|---|
| `GET`    | `/api/admin/users` | Paginated list. `?pageSize=10\|20\|30\|ALL`, defaults to 20. Returns `UserResponse` (no `passwordHash`). |
| `GET`    | `/api/admin/users/search?q=` | Case-insensitive LIKE across username, firstname, lastname, email |
| `GET`    | `/api/admin/users/{id}` | One user |
| `POST`   | `/api/admin/users` | Create user with explicit `isAdmin` role; body `{ username, password, firstname?, lastname?, email?, isAdmin }` |
| `PUT`    | `/api/admin/users/{id}` | Update identity + role; all fields optional (omitted = unchanged) |
| `DELETE` | `/api/admin/users/{id}` | Delete user; CASCADE removes their profiles and sessions |
| `GET`    | `/api/admin/users/{id}/sessions` | List a user's live + recent auth sessions |
| `POST`   | `/api/admin/users/{id}/sessions/revoke` | Force-logout (returns `{ revoked: <count> }`) |
| `POST`   | `/api/admin/users/{id}/password` | Admin password reset (revokes all existing sessions) |
| `GET`    | `/api/admin/audit` | Query `audit_log`; filters `?actor=&target=&action=&since=&page=&pageSize=`; `since` is ISO-8601 |
| `GET`    | `/api/admin/dashboard/overview` | Local SQL aggregates (user / profile / audit counts, 7d/30d growth) |
| `GET`    | `/api/admin/dashboard/users/{id}` | Per-user drilldown: profile list, recent sessions, last 20 audit events |
| `GET`    | `/api/admin/dashboard/honcho` | All profiles + parallel reachability probe (5s timeout per profile) |
| `GET`    | `/api/admin/dashboard/honcho/{profileId}` | Per-profile Honcho drilldown: queue + workspace |
| `GET`    | `/api/admin/maintenance/status` | Row counts + retention config |
| `POST`   | `/api/admin/maintenance/audit/purge` | Manual `AuditRetentionJob` trigger; returns deleted counts |
| `POST`   | `/api/admin/maintenance/sessions/purge-expired` | Manual sweep of `auth_sessions` where `expires_at < now` |

**Self-protection rules** (enforced in `AdminUserService`): the last remaining admin cannot be demoted or deleted (returns 409), and a user cannot demote or delete themselves (returns 409). Recovery is via direct DB edit (`UPDATE users SET is_admin = 1 WHERE id = ...`).

**Audit log.** Every admin write path calls `AdminAudit.record(...)` with `actorUserId`, `action`, `targetUserId`, `ip`, `sessionId`, and a JSON-encoded metadata map. Recorded actions: `user.bootstrap`, `user.create`, `user.update`, `user.delete`, `user.sessions.revoke`, `user.password.reset`, `audit.purge`, `sessions.purge`. The `audit.purge` action records the deleted counts so the operator has a permanent record of every retention run (a no-op run is intentionally not audited to avoid daily noise).

## Deployment

### Install via package manager (recommended)

| OS | Command | Service file |
|---|---|---|
| macOS | `brew install cloudbsdorg/honcho-inspector/honcho-inspector` | `~/Library/LaunchDaemons/com.honcho.inspector.plist` |
| Linux (Homebrew/Linuxbrew) | `brew install cloudbsdorg/honcho-inspector/honcho-inspector` | `/etc/systemd/system/honcho-inspector.service` |
| FreeBSD (ports) | `cd /usr/ports/net/honcho-inspector && make install clean` | `/usr/local/etc/rc.d/honcho_inspector` |
| FreeBSD (pkg) | `pkg install honcho-inspector` | `/usr/local/etc/rc.d/honcho_inspector` |

The package-manager install creates the dedicated service user
(`www-data` on Linux, `www` on FreeBSD, `_www` on macOS), the
directories, the man page, and the service file. The operator only
needs to set `HONCHO_CRYPTO_KEY` (and optionally the bootstrap
admin credentials) in `/etc/default/honcho-inspector` and start the
service.

### Install manually (POSIX)

For operators who do not use a package manager, the project ships
[`bin/install-honcho-inspector`](bin/install-honcho-inspector). Run
it as root after `mvn package`:

```bash
mvn -B -ntp package -DskipTests
sudo bin/install-honcho-inspector
sudo $EDITOR /etc/default/honcho-inspector   # set HONCHO_CRYPTO_KEY
sudo systemctl restart honcho-inspector      # Linux
# or:  sudo service honcho_inspector restart # FreeBSD
# or:  sudo launchctl kickstart -k system/com.honcho.inspector  # macOS
```

The script is idempotent: re-running after an upgrade re-installs
the jar and the service file. It does NOT overwrite the database,
the operator-edited `application.yml`, or `/etc/default/honcho-inspector`
unless `--recreate-config` is passed.

### Reverse proxy

The backend listens on plain HTTP and **must** sit behind a
TLS-terminating reverse proxy in any internet-reachable environment.
The proxy is responsible for HTTPS, HSTS, security headers, and rate
limiting. The backend itself should bind to `127.0.0.1` so it is not
reachable except from the proxy.

Ready-to-use example configs for **nginx** (primary, certs managed by
nginx via certbot), **Apache** (certbot-managed), and **Caddy**
(cert-managed automatically by Caddy itself) live in
[`docs/reverse-proxy.md`](docs/reverse-proxy.md). Pick one and follow
the requirements checklist at the bottom of that file.

Production hardening (TLS, headers, secret storage, file permissions) is
catalogued in [`docs/SECURITY.md`](docs/SECURITY.md).

## Build, test, verify

```bash
mvn test                  # 449 unit + slice + integration tests; <90s
mvn package               # builds the fat jar at target/honcho-inspector-backend-0.1.0-SNAPSHOT.jar
mvn verify                # full verify, includes test + OpenAPI drift check
java -jar target/honcho-inspector-backend-0.1.0-SNAPSHOT.jar
```

The test suite uses an in-memory SQLite (`HONCHO_DB_PATH=jdbc:sqlite::memory:`) and a fixed crypto key. The new admin RBAC surface has dedicated coverage: `AdminAuthInterceptorTest` (8 unit tests on the annotation + interceptor), `AdminUserServiceTest` (23 unit tests on create / update / delete / revoke / reset-password with every self-protection rule), `AdminUserControllerTest` (21 MockMvc tests on every endpoint, pagination, 401/403/404/409), `AdminAuditControllerTest` (9 tests on filter combinations), `AdminBootstrapTest` (4 tests on the config-file-driven first admin), `AuditRetentionJobTest` (4 tests on age + size-cap purging), `AdminDashboardServiceTest` (9 unit tests including partial fan-out failures), `AdminDashboardControllerTest` (7 MockMvc tests), and `AdminMaintenanceControllerTest` (7 tests on manual purge + status).

## Security model

- **No API key in the browser.** The browser only ever sees the session ID. The Honcho API key lives encrypted in the SQLite DB, decrypted only on the server when proxying.
- **Sessions are 24-byte random hex**, stored in `auth_sessions` table with `last_seen_at` and optional `expires_at` (driven by `SESSION_TTL_MINUTES`).
- **Passwords are bcrypt** with cost 12.
- **API keys are AES-256-GCM** with a 12-byte random IV per encryption. The key is `HONCHO_CRYPTO_KEY` (base64 32 bytes). If unset, the server logs a warning and uses an ephemeral random key — values are lost on restart.
- **No CSRF tokens needed** — the API requires `X-Session-Id` (custom header), not cookies. Browsers will refuse to set the header cross-origin without explicit CORS opt-in.
- **CORS is whitelist-only** via `CORS_ALLOWED_ORIGINS`. Origins are matched exactly, no wildcards.
- **No open sign-up.** `POST /api/auth/register` is `@RequireAdmin`; the public `SessionAuthFilter` no longer permits it. The first admin is created by `AdminBootstrap` from `honcho.bootstrap.*` in the drop-in config. All subsequent users are created by an existing admin via `POST /api/admin/users`.
- **Central admin gate.** The `@RequireAdmin` annotation on a controller (class-level or method-level) is enforced by the `AdminAuthInterceptor` HandlerInterceptor for every request to that handler. Static resources, public auth paths, and the Springdoc `/v3/api-docs` surface pass through with zero overhead. There is no per-controller opt-in helper — the gate is uniform.
- **Self-protection.** The last remaining admin cannot be demoted or deleted, and a user cannot demote or delete themselves. Both return 409. Enforced in `AdminUserService.update/delete`, so the rule is independent of the controller layer.
- **Audit log.** Every admin write path and every user-management mutation records an entry in `audit_log` (fire-and-forget; a broken audit table never breaks the calling write path). Retention is 90 days by age, or 1,000,000 rows by size, whichever fires first; the daily sweep runs at 3:00 AM local. Manual trigger: `POST /api/admin/maintenance/audit/purge`.
- **`/api/health` is a no-information liveness probe.** It returns only `{ok, needs_register}`. Aggregate user / session / profile counts are exclusively available to admins via `GET /api/admin/dashboard/overview`.

For the full threat model, audit findings (with severity + remediation
guidance), and the operator hardening checklist, see
[`docs/SECURITY.md`](docs/SECURITY.md). For reverse-proxy / TLS / header
configurations to put in front of this service, see
[`docs/reverse-proxy.md`](docs/reverse-proxy.md).

### Reporting a vulnerability

Email `security@revyt...` (TBD by the project owner). Do not file
public GitHub issues for suspected vulnerabilities. The placeholder is
in [`docs/SECURITY.md`](docs/SECURITY.md) §7 — update both before tagging
a release.

## Dev workflow

- `mvn spring-boot:run` for local with hot-reload (devtools is on for `runtime` scope)
- `mvn test` before every commit
- The `ApplicationReadyEvent` fires a one-line `StartupInfoLogger` summary at boot with the port, profiles, config dir, Honcho upstream, CORS, and session TTL — useful for prod diagnostics
- `mvn verify` before tagging a release

## Repo layout

```
src/main/java/com/honcho/dashboard/
  DashboardApplication.java
  auth/         — User, Profile, AuthSession, PasswordHasher, CryptoService,
                   AuthService, AuthController,
                   AdminUserService, AdminUserController, AdminAudit,
                   AdminAuditController, AdminBootstrap, AuditLogDao,
                   AdminDashboardService, AdminDashboardController,
                   AuditRetentionJob, AdminMaintenanceController,
                   RequireAdmin, PageSize, *Dao
  config/       — CorsConfig, HonchoConfigDirResolver, HonchoProperties,
                   HttpClientConfig, StartupInfoLogger, AdminAuthConfig
  controller/   — HonchoController (the proxy)
  filter/       — SessionAuthFilter
  auth/         — AdminAuthInterceptor
  model/        — HonchoContext, ErrorResponse
  service/      — HonchoProxyService (the actual upstream calls)
src/main/resources/
  application.yml
  schema.sql
bin/
  honcho-inspector                 — POSIX launcher; detects OS and sets HONCHO_CONFIG_DIR
  install-honcho-inspector         — POSIX install script (creates user, dirs, service file, man page)
etc/honcho-inspector/
  application.yml.example          — drop-in config template (bootstrap + audit blocks)
etc/systemd/
  honcho-inspector.service         — Linux systemd unit (User=www-data, hardened)
etc/rc.d/
  honcho-inspector                 — FreeBSD rc.d script (honcho_inspector_user=www)
etc/launchd/
  com.honcho.inspector.plist       — macOS launchd plist (UserName=_www)
packaging/
  homebrew/
    honcho-inspector.rb            — Homebrew formula (macOS + Linuxbrew)
  linux/
    README.md                      — Linux packaging notes (.deb / .rpm / Linuxbrew)
docs/
  SECURITY.md                      — threat model, audit findings, hardening checklists
  reverse-proxy.md                 — nginx (primary), Apache, Caddy configs + requirements
  honcho-providers.md              — provider layer architecture, custom-provider tutorial, V4 walkthrough
  regenerating-openapi.md          — when/how to refresh docs/openapi.yaml + drift-check policy
  openapi.yaml                     — hand-written OpenAPI 3 narrative contract (admin surface included)
  openapi.generated.json           — springdoc snapshot (CI drift-checks against openapi.yaml)
  honcho-inspector.1               — groff man page for the operator
  lessons/
    os-config-conventions.md       — why we use OS-aware config dirs, not ~/.X
.github/workflows/
  ci.yml                           — mvn test + OpenAPI drift check on push/PR
```

## License

BSD 3-Clause. See [LICENSE](LICENSE).

Copyright (c) 2026, REVYTECH, Inc.

## Operator documentation

A standalone groff man page is at [`docs/honcho-inspector.1`](docs/honcho-inspector.1) covering synopsis, the `honcho.bootstrap.*` / `honcho.audit.*` configuration blocks, file locations, the `/api/admin/*` surface, the daily `AuditRetentionJob` sweep, and recovery from a lost-admin lockout. Render it with:

```bash
groff -man -Tutf8 docs/honcho-inspector.1 | less
# or, after `make install` (FHS):
man honcho-inspector
```
