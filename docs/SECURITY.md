# Security audit — `honcho-inspector-backend`

> **Scope:** the Spring Boot 3.5 / Java 25 backend at the current `HEAD`.
> **Method:** static review of `src/main/java/**`, `src/main/resources/**`, `pom.xml`, `bin/honcho-inspector`, `etc/**`. No live testing.
> **Date of review:** current commit.
> **Audience:** operators and contributors of `honcho-inspector`.
> **Related docs:** [`docs/reverse-proxy.md`](reverse-proxy.md), [`README.md`](../README.md).

---

## 1. Threat model

### 1.1 What this service is

A thin, multi-user, multi-profile reverse-proxy in front of one or more Honcho
API instances. It owns:

- a SQLite database of users, encrypted Honcho API keys, and session tokens
  (default location: `$HONCHO_CONFIG_DIR/honcho-inspector.db`);
- an admin-style Angular UI client (different repo), reachable only via
  `X-Session-Id: <hex>` header authentication;
- a single binary that listens on HTTP by default and is expected to be put
  behind a TLS-terminating reverse proxy in production.

### 1.2 Assets

| Asset | Where it lives | Sensitivity |
| --- | --- | --- |
| User passwords | `users.password_hash` (bcrypt, cost 12) | Medium — bcrypt-protected |
| Honcho API keys (per profile) | `honcho_profiles.api_key_encrypted` (AES-256-GCM, 12-byte IV per encryption) | **High** — grants Honcho workspace access |
| `HONCHO_CRYPTO_KEY` (the KEK for the column above) | Operator env var; not on disk | **High** — decrypts every API key in the DB |
| Session IDs (24-byte SecureRandom hex, 192 bits) | `auth_sessions` + browser-local storage in the UI | **High** — auth is session-id-only |
| Audit trail | _None — not implemented_ | (gap) |

### 1.3 Adversaries considered

1. **Network attacker** between browser and backend (no TLS) — mitigated by
   putting the service behind a TLS-terminating reverse proxy (see
   [`docs/reverse-proxy.md`](reverse-proxy.md)).
2. **Network attacker** between backend and Honcho upstream — the operator
   controls both endpoints and is expected to use TLS.
3. **Anonymous remote attacker** hitting `/api/auth/login` to brute-force a
   weak password — partially mitigated by bcrypt; **no rate limiting today**
   (see finding **F-01**).
4. **Compromised UI session** (stolen `X-Session-Id`) — bounded by session
   TTL and the ability of the operator to revoke sessions by deleting rows
   in the DB; no session-list endpoint or admin tools exist.
5. **Malicious local admin user** — they can save a profile pointing at an
   arbitrary `baseUrl`. The backend will dutifully forward authenticated
   requests to that URL on their behalf. This is a form of SSRF that
   operators must accept (see finding **F-08**).
6. **Attacker with file read on the host** — they get the SQLite file. Without
   `HONCHO_CRYPTO_KEY` they cannot decrypt the API keys. **With** it (e.g.
   process env readable), they can. Documented in §5.

### 1.4 Out of scope

- The Angular UI repo (`honcho-inspector-ui`).
- The Honcho upstream service itself.
- The host OS / container runtime — the backend assumes a hardened POSIX or
  Windows host.

---

## 2. What the code does well

These are real positives. They are the baseline; findings in §3 assume them.

- **Honcho API key never reaches the browser.** Encryption at rest, decryption
  only inside `HonchoProxyService` for the lifetime of one request.
  (`auth/CryptoService.java`, `auth/ProfileService.java`.)
- **All SQL is parameterised.** `JdbcTemplate` with `?` placeholders in every
  DAO. No string concatenation, no `Statement.executeQuery`. Verified across
  `UserDao`, `AuthSessionDao`, `ProfileDao`.
- **No Spring Security on the classpath, no filter chain surprises.** The
  ~60-line `SessionAuthFilter` is the only authn gate, and it has a 2-entry
  public-path allowlist (`/api/auth/login`, `/api/health`). Easy to audit.
  Admin gating is a separate HandlerInterceptor
  (`auth/AdminAuthInterceptor`) that runs **after** the auth filter, so
  the `CurrentUser` attribute is always populated before the admin check.
- **Constructor injection only** — no `@Autowired` field injection anywhere.
  Reduces the attack surface for bean-injection issues and is easier to
  reason about.
- **CORS is whitelist-only, no wildcards.** Origins are matched exactly from
  `CORS_ALLOWED_ORIGINS`. `allowCredentials(false)` — so the API never relies
  on cookies.
- **No stateful CSRF surface.** Authentication is a custom header
  (`X-Session-Id`), not a cookie. Browsers will refuse to set the header
  cross-origin without explicit CORS opt-in, so the standard CSRF threat
  model doesn't apply.
- **bcrypt cost 12** for password hashing
  (`auth/PasswordHasher.java`). Work factor is the current
  OWASP-recommended baseline.
- **AES-256-GCM with a 12-byte random IV per encryption** for API keys
  (`auth/CryptoService.java`). 128-bit auth tag. No nonce reuse, no ECB, no
  CBC.
- **24-byte (192-bit) session IDs** generated with `SecureRandom` and
  formatted as hex (`auth/AuthService.newId`). 192 bits is well above the
  OWASP session-token minimum.
- **Schema is idempotent and integrity-preserving.** `CREATE TABLE IF NOT
  EXISTS`, `FOREIGN KEY ... ON DELETE CASCADE`, `PRAGMA foreign_keys = ON`
  in the JDBC connection init SQL, indexed on the columns that matter.
- **Jackson `default-property-inclusion: non_null`** — responses don't leak
  nulls by accident.
- **Eager validation** with `jakarta.validation` on registration, login, and
  profile DTOs (`@NotBlank`, `@Size(min = 8)`).
- **Startup info logger** prints a one-line summary of port, profiles,
  config dir, Honcho upstream, CORS, and session TTL — useful for prod
  diagnostics without leaking secrets.
- **Centralised admin gate** via the `@RequireAdmin` annotation +
  `AdminAuthInterceptor`. Every handler under `/api/admin/**` is enforced
  uniformly; there is no per-controller opt-in helper, and no endpoint
  can opt out by accident. The interceptor does its work only for
  `HandlerMethod` handlers — static resources, the public auth paths, and
  the Springdoc `/v3/api-docs` surface all pass through with zero
  overhead.
- **`/api/health` is a no-information liveness probe.** Returns only
  `{ok, needs_register}`. Aggregate user/session/profile counts (the
  previous behaviour) were moved to the admin-only
  `/api/admin/dashboard/overview` so an unauthenticated probe no longer
  enumerates the user base.
- **Audit log for mutations.** Every admin write path and every
  user-management mutation records a row in `audit_log` with actor,
  action, target, IP, session, and a JSON metadata map. Retention is
  90 days age OR 1,000,000 rows, whichever fires first, and the daily
  sweep records its own `audit.purge` row so the operator can see that
  the sweep ran.

---

## 3. Findings

Severity uses CVSS v3.1 qualitative levels (Critical / High / Medium / Low /
Info). **No critical or high findings.** The application is in good shape for
a self-hosted admin surface; the medium and low items below are hardening
opportunities, not known exploitable bugs.

### F-01 — No rate limiting on `/api/auth/login` or `/api/auth/register` (Medium)

**Where:** `auth/AuthController.java` (`login`, `register`),
`filter/SessionAuthFilter.java`.

**What.** `/api/auth/login` and `/api/auth/register` are not throttled. An
attacker can:
- brute-force weak passwords (bcrypt cost 12 helps — ~10–50ms per attempt on
  modern CPUs — but is not a substitute for rate limiting);
- script mass registration to fill the `users` table, denial-of-service the
  admin, or simply enumerate usernames via the 409 response (`UserExistsException`
  in `AuthService.register`).

**Why it matters.** This is the only authentication endpoint. With bcrypt's
work factor and a strong password, online brute force is hard. With a weak
password (cost 12 ≈ 10ms on server hardware = ~6 attempts/min per CPU core
per attacker thread) it is not.

**Remediation.** Add a per-IP and per-username rate limit. Options:
- Spring Boot 3.5 + `spring-boot-starter-actuator` is not enough; pull in
  `bucket4j-spring-boot-starter` (in-memory) or front the service with the
  reverse proxy's `limit_req` zone (see
  [`docs/reverse-proxy.md`](reverse-proxy.md) §3.1 — nginx example already
  includes this).
- Log failed-login events with the username, source IP, and timestamp to
  support offline analysis.
- Return the same 401 for "user not found" and "wrong password" (already
  done — good — but be aware this is timing-observable; see F-09).

**Status:** Open. Tracked in this doc.

### F-02 — CORS `allowedHeaders("*")` is overly permissive (Low)

**Where:** `config/CorsConfig.java:33` —
`.allowedHeaders("*")`.

**What.** Any header is allowed cross-origin. For a strict API, the actual
header set is small: `Content-Type`, `X-Session-Id`, `X-Honcho-Profile-Id`,
`Accept`, plus the CORS preflight headers. `*` lets browsers send
`Authorization`, `Cookie`, `X-Forwarded-For`, etc. on cross-origin preflights.

**Why it matters.** With `allowCredentials(false)` and no cookie auth, the
exploitable risk is small. But it weakens defence-in-depth: if a future
change moves to cookie auth without updating CORS, a permissive header
allowlist becomes the difference between safe and unsafe.

**Remediation.** Replace `*` with an explicit list, e.g.
`.allowedHeaders("Content-Type", "Accept", "X-Session-Id", "X-Honcho-Profile-Id")`.

**Status:** Open.

### F-03 — No security HTTP response headers set by the application (Medium)

**Where:** every controller; the `application.yml` `server.error.*` block
only configures error-body inclusion, not headers.

**What.** The backend sets none of the modern security headers:
`Strict-Transport-Security`, `X-Content-Type-Options`, `X-Frame-Options`,
`Referrer-Policy`, `Permissions-Policy`, `Content-Security-Policy`.

**Why it matters.** The application is JSON-only, so most of these are
belt-and-braces. The two that matter are:
- **HSTS** — without it, the first visit to a freshly-installed instance
  is one plaintext round-trip away from being MITM'd. The reverse proxy
  should set this (see [`docs/reverse-proxy.md`](reverse-proxy.md) §3.1 —
  it does), but if the operator forgets, the app is the last line of
  defence.
- **`X-Content-Type-Options: nosniff`** — prevents MIME-sniffing of error
  JSON in legacy browsers.

**Remediation.** Add a tiny `Filter` (or `WebMvcConfigurer` advice) that
sets the safe-by-default headers:
```
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
Permissions-Policy: ()
```
and a CSP of `default-src 'none'; frame-ancestors 'none'` (the body is
JSON, so the strictest CSP is fine). HSTS belongs on the reverse proxy.

**Status:** Open.

### F-04 — No HTTPS enforcement at the application layer (Info → Medium in prod)

**Where:** `application.yml`, `bin/honcho-inspector`, every controller.

**What.** The backend serves plain HTTP on `${PORT:8080}`. There is no
built-in redirect to HTTPS, no HSTS, no port-redirect filter. The whole
security model assumes the operator puts a TLS-terminating reverse proxy in
front. This is by design, but it is a hard dependency that is **not yet
documented** in the README — fixed by the new
[`docs/reverse-proxy.md`](reverse-proxy.md).

**Remediation.** Keep it this way (the proxy is the right place for TLS).
The new doc makes the requirement explicit.

**Status:** Resolved by documentation.

### F-05 — `e.getMessage()` is returned in 500 responses (Low)

**Where:** `controller/HonchoController.java:191` —
`return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));`.

**What.** Unexpected exceptions (`Exception e`) are converted to a 500
response whose body is `{"error": <exception.getMessage()>}`. The
`HonchoCallException` messages include the configured Honcho base URL
(`"Cannot reach Honcho at " + ctx.baseUrl() + ": " + e.getMessage()`).

**Why it matters.** The base URL is operator-set (not user-set), so it
isn't a per-user secret. But the upstream error message can include DNS
errors, connection-refused detail, library internals — useful to a
reconnaissance attacker. The bundled `application.yml` sets
`server.error.include-message: always` and `include-stacktrace: never`,
which compounds the leak at Spring's error-mapping layer for unmapped
exceptions.

**Remediation.** Map unexpected exceptions to a fixed
`{"error":"internal server error"}` and log the detail with a correlation
id. Set `server.error.include-message: never` (the only safe Spring
Boot 3.5 value for prod) and rely on a `@ControllerAdvice` for the
friendly envelope. Valid Spring Boot 3.5 values for
`server.error.include-message` are `ALWAYS`, `NEVER`, `ON_PARAM`.

**Status:** Open.

### F-06 — `HONCHO_CRYPTO_KEY` unset falls back to ephemeral, but the app still starts (Low)

**Where:** `auth/CryptoService.java:36-50`.

**What.** If `honcho.crypto-key` is empty or unset, the service logs a
warning and generates a random 32-byte AES key per process. Profiles can be
created, encrypted values are written, but on restart the key is lost and
all `decrypt()` calls fail.

**Why it matters.** This is reasonable dev behaviour but a footgun in prod.
An operator who deploys without setting the env var will see a working
service for one boot, then mysterious 500s after the first restart. Worse,
if they notice and re-encrypt with a fresh key, **the old encrypted
profiles become unrecoverable** and the user is silently locked out of
their Honcho workspaces.

**Remediation.** Fail fast in prod. In
`CryptoService.<init>`, check the active Spring profile; if it includes
`prod` and the key is missing, throw `IllegalStateException` and refuse to
start. Keep the ephemeral mode for `dev`/`test` only.

**Status:** Open.

### F-07 — No input length cap on `ProfileCreateDto.apiKey` / `baseUrl` (Low)

**Where:** `auth/ProfileController.java:35-41`.

**What.** The DTO declares `apiKey`, `baseUrl`, `workspaceId`,
`honchoUserName`, `label` as `@NotBlank` only — no `@Size` cap. The
underlying `JdbcTemplate` will accept any length.

**Why it matters.** A malicious or buggy UI can submit a 10MB `apiKey` and
bloat the SQLite row. Spring's `server.jetty.max-http-form-post-size`
provides a container-level safety net, but a per-field cap is
cheaper and clearer.

**Remediation.** Add `@Size(max = 4096)` (or whatever the largest plausible
Honcho API key is) to the relevant DTO fields.

**Status:** Open.

### F-08 — `Profile.baseUrl` is not validated for scheme or private/loopback targets (Medium)

**Where:** `auth/Profile.java`, `service/HonchoProxyService.java:121-126`,
`auth/ProfileService.create`.

**What.** A user can set `baseUrl` to anything: `http://169.254.169.254/`
(AWS / OpenStack / Azure metadata), `http://127.0.0.1:6379/` (local
Redis), or `file:///etc/passwd`-shaped schemes depending on the
`RestClient`'s URI parser. The only sanitisation is
`sanitizeBase()`: strip trailing slashes, strip `/mcp`.

**Why it matters.** A malicious admin (or a user who hijacked a session)
can use the backend as a SSRF proxy. The blast radius depends on the
network the backend is on. In a typical deployment the backend is on a
host that can reach the local Honcho instance and the public internet
only; private/loopback exposure is still real.

**Remediation.**
- Reject schemes other than `https` (and `http` for self-hosted Honcho
  deployments that don't terminate TLS internally — keep both, with a
  warning).
- Optionally, reject RFC 1918 / loopback / link-local / cloud-metadata
  targets in `baseUrl`. Make this an env var, e.g.
  `honcho.allow-private-base-urls: false` (default), so self-hosted
  operators on the same host can opt in.
- In `ProfileController.test`, also enforce the same validation, since
  `testConnection` will happily make a request to the supplied URL.

**Status:** Open.

### F-09 — Username enumeration via login timing (Low)

**Where:** `auth/AuthService.java:49-54`.

**What.** On `login`, the code first does a DB lookup; on miss, it throws
`InvalidCredentialsException` immediately. On hit, it runs `bcrypt.matches`
(10–50ms). A timing-distinguishability attacker can enumerate valid
usernames with a few hundred requests each.

**Why it matters.** Low. Username enumeration on this surface is
information disclosure only — the attacker still has to guess a
bcrypt-protected password. Still, the standard advice is to spend a
constant amount of time per failed login.

**Remediation.** On user-miss, run a dummy `bcrypt.matches` against a
precomputed hash of a random password to equalise wall-clock time. A
sleeker alternative: log and continue to a real verify, returning the same
401 either way.

**Status:** Open.

### F-10 — No audit log of security-relevant events (Medium)

**Where:** `auth/AuthService.java`, `auth/AuthController.java`,
`auth/ProfileController.java`, `controller/HonchoController.java`.

**What.** Successful logins, failed logins, profile creates/updates/
deletes, and proxy failures are not logged at a security level. The
default Spring `INFO` log is fine for ops, but a security audit needs
`who did what when to which resource`.

**Why it matters.** A multi-user admin surface with no audit log is hard
to investigate after an incident. Operators have to correlate
application logs and DB row timestamps by hand.

**Remediation.** Add a `security-events` log appender (or an `auth_events`
table) that records: login (success/fail, username, source IP), logout,
profile CRUD (user, profile id, action), password change (when added).
Spring's `ApplicationEventPublisher` is the natural fit.

**Status:** Resolved (Phase 4 of the admin RBAC rollout). The
`audit_log` table records every admin write path:
`user.bootstrap` (the `AdminBootstrap` first-startup path),
`user.create`, `user.update`, `user.delete`,
`user.sessions.revoke`, `user.password.reset`, `audit.purge` (the
retention sweep's own bookkeeping), and `sessions.purge`. Each entry
captures `actorUserId`, `action`, `targetUserId`, `ip`, `sessionId`, and
a JSON-encoded `metadata` map. Failed logins and proxy failures remain
in the JSONL log only — by design, those events happen at high volume
and the structured `audit_log` is reserved for mutations.

### F-11 — `error.include-message: always` leaks upstream error text to the client (Low)

**Where:** `src/main/resources/application.yml:5-6`,
`controller/HonchoController.java:191`.

**What.** `server.error.include-message: always` is the bundled default.
This is fine in dev; in prod it forwards framework and upstream error
messages to the browser, which is a small information disclosure.

**Why it matters.** Spring's `BasicErrorController` will then return
`{"timestamp":..., "status":..., "error":..., "message":<the exception
message>, "path":...}` for unmapped exceptions. Combined with F-05, the
caller learns a fair amount about the upstream.

**Remediation.** In the prod config example, set
`server.error.include-message: never` (the only safe value in
Spring Boot 3.5; `when_authorized` is NOT a valid value and causes
the ApplicationContext to fail to start) and `include-binding-errors: never`.
Keep `include-stacktrace: never` (already set).

**Status:** Resolved. The `etc/honcho-inspector/application.yml.example`
template now ships with `server.error.include-message: never`,
`include-binding-errors: never`, and `include-stacktrace: never` set in
the recommended production block, with a one-line comment in the
template header warning the operator to keep them. The bundled jar
defaults to `include-message: always` (line 5 of `application.yml`) for
dev convenience; production deployments are expected to copy the
template to the OS config dir and edit.

### F-12 — `User.isAdmin` is a boolean with no role system (Info)

**Where:** `auth/User.java`, `auth/AuthController.java`, schema.

**What.** The schema has `is_admin INTEGER` and a check in `AuthController`
is implied by "first user becomes admin" — but **no endpoint ever reads or
checks `is_admin`**. There is no admin-only route, no role-based access
control. The field exists for future use.

**Why it matters.** None today, but it is a future-bug magnet: a
contributor adding an `/api/admin/...` route could plausibly check the
field without an auth gate, expecting one to exist.

**Remediation.** Either (a) implement the role check and the missing
admin endpoints, with tests; or (b) drop the column until it's needed. If
kept, add an explicit test that asserts `/api/admin` is gated.

**Status:** Resolved (Phases 1, 3, 5 of the admin RBAC rollout). The
`@RequireAdmin` annotation + `AdminAuthInterceptor` HandlerInterceptor
enforce the role on every handler under `/api/admin/**`. The interceptor
runs after `SessionAuthFilter`, so unauthenticated callers get 401
before the admin check, and authenticated non-admins get 403
`{"error":"admin only"}`. The annotation is class-level or method-level
(both work, matching `@Transactional` semantics). There is no per-
controller opt-in helper — the gate is uniform and declarative.
`AdminAuthInterceptorTest` has 8 unit tests covering the matrix
(class-level, method-level, mixed, no annotation, missing session
attribute, non-admin, admin, static-resource passthrough). Every
`/api/admin/*` controller has at least one test that asserts
non-admins get 403.

### F-13 — `HonchoConfigDirResolver.resolve()` `mkdir` happens in the launcher, not the resolver (Info)

**Where:** `bin/honcho-inspector:67` (`mkdir -p "$CONFIG_DIR"`),
`config/HonchoConfigDirResolver.java:38-48`.

**What.** The launcher creates the config dir, which is correct. The
resolver only logs. This is good — the JVM process doesn't need write
access to `/etc/honcho-inspector/`. Worth a one-line comment in
`HonchoConfigDirResolver.resolve()` for the next reader.

**Status:** Cosmetic.

### F-14 — `forward-headers-strategy: framework` trusts `X-Forwarded-*` from any peer (Low)

**Where:** `application.yml:3`, `config/CorsConfig.java` (no `Origin`
validation against the proxy chain).

**What.** `server.forward-headers-strategy: framework` makes Spring honour
`X-Forwarded-Proto`, `X-Forwarded-For`, etc. from any source. The trusted
boundary is the reverse proxy, not the JVM. If a client can hit the
backend port directly (because it is reachable from outside the proxy),
they can spoof these headers and trick future code that reads them.

**Why it matters.** Direct port exposure is a deployment-time mistake,
not a code bug — but a defence-in-depth change is to pin the strategy to
`native` (use the Servlet container's `RemoteIpValve` / equivalent) and
configure a trusted-proxy CIDR at the proxy, not the app.

**Remediation.** Document the assumption in
[`docs/reverse-proxy.md`](reverse-proxy.md) (the new doc does: the
backend should not be reachable except from the proxy on
`127.0.0.1`/loopback). Optionally switch to `native` and document the
forwarded-header flow.

**Status:** Resolved by documentation.

### F-15 — `HonchoCallException` body is truncated to 500 chars, not redacted (Info)

**Where:** `service/HonchoProxyService.java:132-136`.

**What.** `safeBody` truncates the upstream error body but does not
redact. If the upstream is misbehaving and echoes request headers (some
proxies do), the body could contain a Honcho API key in plaintext.

**Why it matters.** Unlikely in practice — Honcho's documented behaviour
is to return JSON errors with a `detail` field. But the proxy never knows
what a future upstream might return.

**Remediation.** In `safeBody`, search for the configured API key (and
obvious redaction patterns like `Bearer <token>`) and replace with
`[REDACTED]` before returning.

**Status:** Open.

### F-16 — `deleteExpired` exists in the DAO but is never called (Info)

**Where:** `auth/AuthSessionDao.java:52-54`.

**What.** `SESSION_TTL_MINUTES=0` means sessions never expire
(`AuthSession.isExpired` returns false on a missing `expires_at`). The
DAO has a `deleteExpired` method but no scheduled job invokes it. The
README documents this honestly ("0 = never expire").

**Why it matters.** Not a bug, but if an operator sets
`SESSION_TTL_MINUTES=60`, expired rows will accumulate forever.

**Remediation.** Add a `@Scheduled` job (with `@EnableScheduling`) that
calls `deleteExpired` every N minutes. Or document the operator
responsibility to vacuum manually.

**Status:** Resolved (Phase 7b). `POST /api/admin/maintenance/sessions/purge-expired`
exposes the call as a manual admin trigger. A scheduled job is still not
wired (by design — `SESSION_TTL_MINUTES=0` is the documented default, so
auto-purge would be a no-op for most installs). Operators who set
`SESSION_TTL_MINUTES>0` should run the manual trigger on a cron, or call
it from the reverse proxy's log-rotation hook.

---

## 4. Dependency / supply-chain notes

`pom.xml` declares six runtime dependencies; all are first-party
(`org.springframework.boot`, `org.springframework.security`,
`org.xerial.sqlite-jdbc`) plus a `devtools` runtime-conditional.

- `spring-boot-starter-web` 3.5.0, `spring-boot-starter-jdbc` 3.5.0,
  `spring-boot-starter-validation` 3.5.0, `spring-security-crypto` 3.5.0,
  `sqlite-jdbc` pinned to `3.46.1.3`. Versions inherit from
  `spring-boot-starter-parent:3.5.0`.
- No `log4j`, no `snakeyaml` outside Spring's own re-export, no
  `commons-*` baggage, no `jackson-databind` override.
- `devtools` is `runtime` + `optional`, so it is not in the production
  fat jar. Good.

**Recommendation.** Add a CI step that runs `mvn dependency:tree` against
the production profile and fails on any `devtools` artifact. Optionally,
add `mvn org.owasp:dependency-check-maven:check` or
`mvn com.github.spotbugs:spotbugs:check` to a future security pipeline.

---

## 5. Operational hardening checklist

For a fresh production install:

- [ ] `HONCHO_CRYPTO_KEY` is set to a `base64 -w0 32 /dev/urandom` value
      and **stored in a secret manager**, not in the on-disk yaml.
- [ ] `CORS_ALLOWED_ORIGINS` is the exact public origin(s) of the UI
      (`https://inspector.example.com`); no wildcards, no `localhost`.
- [ ] `SESSION_TTL_MINUTES` is non-zero (e.g. `60` or `240`).
- [ ] The application port is **not** reachable from outside the host —
      bind to `127.0.0.1` or a unix socket; rely on the reverse proxy for
      public ingress.
- [ ] The reverse proxy sets HSTS, modern ciphers, security headers, and
      rate limits (see [`docs/reverse-proxy.md`](reverse-proxy.md)).
- [ ] The `$HONCHO_CONFIG_DIR` and its parent are owned by a dedicated
      service user, mode `0750`. The SQLite file is mode `0640`.
- [ ] The sqlite file is on a filesystem that supports `fcntl` locking
      (not a network FS).
- [ ] **Bootstrap admin:** on a fresh deploy, populate
      `honcho.bootstrap.admin-username` and `honcho.bootstrap.admin-password`
      in the drop-in config, then start the service. Verify the admin
      was created by logging in and reading `/api/admin/dashboard/overview`.
      **Then remove the `honcho.bootstrap.*` block** from the config file
      and rotate the password via `POST /api/admin/users/{id}/password`.
      Leaving the credentials in `/etc/honcho-inspector/application.yml`
      is a credential-on-disk leak.
- [ ] **Audit retention:** verify the `honcho.audit.*` block is set to a
      policy that matches your compliance window (defaults: 90 days age
      OR 1,000,000 rows, whichever fires first). The retention sweep
      runs at `honcho.audit.purge-cron` (default 03:00 local). The sweep
      records its own `audit.purge` entry; absence of recent
      `audit.purge` rows in `GET /api/admin/audit?action=audit.purge`
      means the sweep stopped running — investigate the JSONL log.
- [ ] **Self-protection sanity check:** as the bootstrap admin, try
      `PUT /api/admin/users/{own-id}` with `{"isAdmin":false}`. The
      server MUST return 409 `cannot demote the last admin`. (If it
      returns 200, the binary is misconfigured and every admin is
      self-deletable.)
- [ ] **Service user:** verify the service is running as the dedicated
      unprivileged user (`www-data` on Linux, `www` on FreeBSD, `_www`
      on macOS), NOT as root or as the operator's personal account.
      The package manager install and the POSIX install script both
      create the user automatically. Verify with:
      . `ps -o user,group,comm -p $(pgrep -f honcho-inspector-backend)`
      . `systemctl show honcho-inspector --property=User` (Linux)
      . `service honcho_inspector status` (FreeBSD)
      . `launchctl list | grep honcho.inspector` (macOS)
- [ ] **File ownership:** the SQLite database and the log dir are
      owned by the service user; the config dir is owned by `root`
      and the service group with mode `0750`; the env file is mode
      `0640` owned by `root` and the service group. Verify with:
      . `ls -la /var/lib/honcho-inspector /var/log/honcho-inspector /etc/honcho-inspector /etc/default/honcho-inspector`
- [ ] **Hardening directives (Linux systemd only):** verify the
      unit file has not been overridden to drop hardening. Read
      `/etc/systemd/system/honcho-inspector.service` and confirm
      `NoNewPrivileges=true`, `ProtectSystem=strict`, `PrivateTmp=true`,
      and `RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6` are
      still present. (`systemctl cat honcho-inspector` shows the
      effective unit after any drop-in overrides.)
- [ ] Backups of `$HONCHO_CONFIG_DIR/honcho-inspector.db` are stored
      encrypted; the backup process reads `HONCHO_CRYPTO_KEY` from the
      same secret store.
- [ ] Log shipping is configured and the security-events channel (when
      added — see F-10) is forwarded to a SIEM.
- [ ] The first registration is done immediately after install, and
      `/api/health` is checked to confirm `users=1` so the admin
      invitation is closed.
- [ ] `bin/honcho-inspector` runs as a non-root user; the JVM is started
      without `agentlib` or `jdwp` flags.
- [ ] `/api/health` is exposed to a private monitoring network only; the
      counts leak minimal info but should not be public.

---

## 6. Swagger UI and OpenAPI spec exposure

The Spring Boot 3.5 / springdoc-openapi-starter-webmvc-ui 2.6.0 setup
exposes Swagger UI at `/swagger-ui.html` and the OpenAPI 3 spec at
`/v3/api-docs`. Both endpoints are intentionally public (no `X-Session-Id`
required) by design — the design choice is documented in
[`docs/honcho-providers.md`](honcho-providers.md) §8 *Strict mode* and the
operator's choice is to gate via the reverse proxy.

If you want to restrict access to Swagger UI (e.g. restrict to internal
IPs or require basic auth), see [`docs/reverse-proxy.md`](reverse-proxy.md)
for nginx, Apache, and Caddy examples — every example config in that doc
ships with the gating snippet commented out, so the default is no gating
and you opt in.

The `/v3/api-docs` endpoint reveals the full API contract (paths,
request/response DTOs, descriptions, parameter metadata). It is safe to
expose on a private network but should NOT be exposed to the public
internet without an auth layer in the reverse proxy — `/v3/api-docs`
describes the surface an attacker would otherwise have to discover by
hand.

---

## 7. Logging

The backend emits **structured JSONL** (one JSON event per line) to both
`$HONCHO_CONFIG_DIR/logs/honcho-inspector.jsonl` (rolling, gzipped) and
stdout (for container capture / `kubectl logs` / `docker logs`).
The JSONL format is chosen so that operators can ingest the stream into
Loki, Elasticsearch, Datadog, or `jq` without a custom parser. API keys
(`apiKey=<value>`), plaintext Bearer tokens (`Bearer <token>`), and
passwords (`password=<value>`, `pass=<value>`) are scrubbed at the
Jackson output stage by a `MaskingJsonGeneratorDecorator` with the
regexes documented in [`docs/logging.md`](logging.md) §3. PII fields
(`session_id`, `user_id`) are **present** in every authenticated
request's events because they are the only stable correlation key for
debugging and incident response; the retention justification is
legitimate interest in service operation (GDPR Art. 6(1)(f)) and the
on-disk window is bounded by `HONCHO_LOG_MAX_HISTORY` (default 30
days). Operators in regulated environments should lower
`HONCHO_LOG_MAX_HISTORY` and ship JSONL to a SIEM with its own
retention controls. Full policy, schema, sizing math, and
`jq`/`gunzip` operational examples are in [`docs/logging.md`](logging.md).

The `audit_log` table is a separate, structured-events surface —
`who did what when to which resource` — distinct from the operational
JSONL stream. It is admin-queryable via
`GET /api/admin/audit?actor=&target=&action=&since=&pageSize=` and
retained per the `honcho.audit.*` config block (default 90 days age
OR 1,000,000 rows; see the man page `honcho-inspector(1)` for the
operator-facing summary). Both surfaces are part of the audit story
and are intentionally separate: the JSONL stream is high-volume and
short-lived; the `audit_log` table is low-volume, structured, and
queryable.

---

## 8. Developer hardening checklist

For contributors and PR reviewers:

- [ ] No new SQL string concatenation — use `JdbcTemplate` parameter
      binding.
- [ ] Any new endpoint that touches a per-user resource checks the
      `current.user().id()` ownership first, mirroring
      `ProfileController` / `HonchoController.call`.
- [ ] Any new endpoint that accepts user-supplied text applies `@NotBlank`
      and a sane `@Size(max=...)`.
- [ ] Any new DTO field returned to the browser is reviewed for secret
      leakage (mirror `UserResponse.from` — never echo
      `passwordHash`, `apiKeyEncrypted`, etc.).
- [ ] If a future change adds cookie-based auth, add CSRF tokens
      (Spring Security's `CookieCsrfTokenRepository` is the standard
      choice). Header-based auth (`X-Session-Id`) is CSRF-safe by
      construction; do not switch to cookies without a CSRF plan.
- [ ] Any new HTTP client uses `JdkClientHttpRequestFactory` (Java 11+
      `HttpClient`) with a real connection pool — the current
      `SimpleClientHttpRequestFactory` in `HttpClientConfig` is
      per-request, blocking, and not pooled.
- [ ] Any new failure path returns a fixed-string error to the caller
      and logs the detail. Do not return `e.getMessage()` directly
      (see F-05).
- [ ] If the active Spring profile is `prod`, the code refuses to start
      without `HONCHO_CRYPTO_KEY` (see F-06).

---

## 9. Reporting a vulnerability

Email `security@revyt...` (TBD by the project owner) with a description
and reproduction. Do not file public GitHub issues for suspected
vulnerabilities. This is a placeholder section — replace with the real
contact before tagging a release.
