# OS Config Directory Conventions for `honcho-inspector`

> **Status:** Active — apply when adding new config dirs, log paths, or runtime files
> **Scope:** `honcho-inspector-backend` (the Java/Spring service) — frontend is static and configures at build/runtime via the proxy
> **Last updated:** when the unified product name was set to `honcho-inspector`

## TL;DR

| OS      | Config dir                                          | Source                                                |
| ------- | --------------------------------------------------- | ----------------------------------------------------- |
| Linux   | `/etc/honcho-inspector/`                                 | FHS — `/etc` is the root config dir for distributions |
| FreeBSD | `/usr/local/etc/honcho-inspector/`                       | Ports/packages convention — `/usr/local/etc` is `/etc` for ports |
| macOS   | `~/Library/Application Support/honcho-inspector/`       | Apple Human Interface Guidelines                      |
| Windows | `%APPDATA%\honcho-inspector\`                            | Microsoft — `AppData\Roaming\<AppName>`               |
| Fallback| `./`                                                | Current working dir, for dev / test                   |

**Product name** everywhere: `honcho-inspector` (kebab-case, one word, hyphenated).
**Repos** stay separate (`honcho-inspector-ui`, `honcho-inspector-backend`) — the user has a strong
preference against monorepos, and the two services have different build chains and
release cadences.

## Why this matters

- A reverse proxy in front of the UI is a production deployment. It needs the
  backend listening on a port the proxy can reach, with CORS that allows the
  proxy's origin.
- An operator shouldn't have to memorize Spring Boot's CLI flags. The launcher
  script (`bin/honcho-inspector`) translates OS culture → `--spring.config.additional-location`.
- Drop-in `application.yml` at the OS path is enough to override port, CORS,
  Honcho base URL, etc. No rebuild.

## Override mechanisms (priority order, high → low)

1. CLI flag passed to `bin/honcho-inspector` after `--`:
   `bin/honcho-inspector -- --server.port=9090`
2. Environment variable — Spring Boot's standard relaxed binding:
   - `PORT` → `server.port`
   - `CORS_ALLOWED_ORIGINS` → `cors.allowed-origins`
   - `HONCHO_BASE_URL` → `honcho.base-url`
   - `HONCHO_API_VERSION` → `honcho.api-version`
   - `HONCHO_REQUEST_TIMEOUT_MS` → `honcho.request-timeout-ms`
   - `HONCHO_CONFIG_DIR` → overrides the OS-aware default entirely
   - `SESSION_TTL_MINUTES` → `session.ttl-minutes`
3. Drop-in file at the OS path:
   `<config-dir>/application.yml` (loaded additively, doesn't replace bundled)
4. The bundled `src/main/resources/application.yml` in the jar

## Frontend

The Angular UI uses relative `/api/...` paths in all environments:

- **Dev** — `proxy.conf.mjs` reads `NG_BACKEND_URL` (default `http://localhost:8080`)
  and rewrites `/api/*` to that origin.
- **Prod** — the reverse proxy serves the static build output and routes `/api/*`
  to the backend on the same host (or a different internal port). No frontend
  rebuild is required to change the API base — the browser only sees `/api`.

A `window.__APP_CONFIG__` runtime override slot exists in the UI for the rare
case where you want to point at a backend on a different path or host without
rebuilding. The reverse proxy can inject this with a `<script>` tag.

## Why we *don't* use `application.yml` in the jar as the source of truth

- The jar is immutable per release. Operators shouldn't have to extract or
  rebuild to change a port.
- The whole point of `/etc/<product>/` is that config and code are separated
  by FHS convention. We honor that.
- In dev (`mvn spring-boot:run`) we use the bundled yaml. In prod (`bin/honcho-inspector`)
  we use the OS-path yaml. The launcher script decides which.

## Anti-patterns (do NOT do this)

- **Don't** read config from `~/.<product>/` on Linux or FreeBSD. That's the
  single-user convention for dotfiles, not a multi-user service config dir.
- **Don't** write logs or state into `/etc/honcho-inspector/`. That's config only.
  Logs go to `<config-dir>/honcho-inspector-backend.log` by default but can be
  redirected with `HONCHO_LOG_DIR`.
- **Don't** hardcode `/etc` in the JVM. Use the resolver. macOS users would
  have a broken install.
- **Don't** require environment variables. The OS path with a sensible default
  is enough; env vars are escape hatches, not the only path.

## See also

- `bin/honcho-inspector` — the launcher that reads this doc
- `etc/honcho-inspector/application.yml.example` — drop-in config template
- `src/main/java/com/honcho/dashboard/config/HonchoConfigDirResolver.java` —
  the code that implements the table above
