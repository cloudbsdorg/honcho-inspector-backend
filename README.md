# honcho-inspector-backend

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

The first user to register becomes admin.

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

All `/api/*` paths under `/api/health` (public), `/api/auth/*` (public), `/api/profiles/*` (session), `/api/{peers,sessions,queue-status,workspace,search,dream}/*` (session + profile header).

### Auth flow

1. `POST /api/auth/register` with `{ username, password }` — first user becomes admin. Returns `201 { id, username, isAdmin, createdAt }`.
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

## Deployment

The backend listens on plain HTTP and **must** sit behind a TLS-terminating
reverse proxy in any internet-reachable environment. The proxy is
responsible for HTTPS, HSTS, security headers, and rate limiting. The
backend itself should bind to `127.0.0.1` so it is not reachable except
from the proxy.

Ready-to-use example configs for **nginx** (primary, certs managed by
nginx via certbot), **Apache** (certbot-managed), and **Caddy**
(cert-managed automatically by Caddy itself) live in
[`docs/reverse-proxy.md`](docs/reverse-proxy.md). Pick one and follow
the requirements checklist at the bottom of that file.

Production hardening (TLS, headers, secret storage, file permissions) is
catalogued in [`docs/SECURITY.md`](docs/SECURITY.md).

## Build, test, verify

```bash
mvn test                  # 29/29 unit + slice tests; <10s
mvn package               # builds the fat jar at target/honcho-inspector-backend-0.1.0-SNAPSHOT.jar
mvn verify                # full verify, includes test
java -jar target/honcho-inspector-backend-0.1.0-SNAPSHOT.jar
```

The test suite uses an in-memory SQLite (`HONCHO_DB_PATH=jdbc:sqlite::memory:`) and a fixed crypto key. `AuthControllerTest` covers register/login/logout/me/profile CRUD/test/reveal; `CorsConfigTest` covers CORS origin parsing; `HonchoConfigDirResolverTest` covers OS detection.

## Security model

- **No API key in the browser.** The browser only ever sees the session ID. The Honcho API key lives encrypted in the SQLite DB, decrypted only on the server when proxying.
- **Sessions are 24-byte random hex**, stored in `auth_sessions` table with `last_seen_at` and optional `expires_at` (driven by `SESSION_TTL_MINUTES`).
- **Passwords are bcrypt** with cost 12.
- **API keys are AES-256-GCM** with a 12-byte random IV per encryption. The key is `HONCHO_CRYPTO_KEY` (base64 32 bytes). If unset, the server logs a warning and uses an ephemeral random key — values are lost on restart.
- **No CSRF tokens needed** — the API requires `X-Session-Id` (custom header), not cookies. Browsers will refuse to set the header cross-origin without explicit CORS opt-in.
- **CORS is whitelist-only** via `CORS_ALLOWED_ORIGINS`. Origins are matched exactly, no wildcards.
- **First user is admin.** The admin flag is a boolean; there is no admin-endpoint for creating users in this version. To make a second user an admin, set `is_admin = 1` directly in the DB.

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
  auth/         — User, Profile, AuthSession, PasswordHasher, CryptoService, AuthService, AuthController, ProfileController, ProfileService, *Dao
  config/       — CorsConfig, HonchoConfigDirResolver, HttpClientConfig, StartupInfoLogger
  controller/   — HonchoController (the proxy)
  filter/       — SessionAuthFilter
  model/        — HonchoContext, ErrorResponse
  service/      — HonchoProxyService (the actual upstream calls)
src/main/resources/
  application.yml
  schema.sql
bin/
  honcho-inspector                 — POSIX launcher; detects OS and sets HONCHO_CONFIG_DIR
etc/honcho-inspector/
  application.yml.example          — drop-in config template
docs/
  SECURITY.md                      — threat model, audit findings, hardening checklists
  reverse-proxy.md                 — nginx (primary), Apache, Caddy configs + requirements
  lessons/
    os-config-conventions.md       — why we use OS-aware config dirs, not ~/.X
```

## License

BSD 3-Clause. See [LICENSE](LICENSE).

Copyright (c) 2026, REVYTECH, Inc.
