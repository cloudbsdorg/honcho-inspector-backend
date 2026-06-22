# Lesson: Admin RBAC rollout on a Spring Boot service

**Topic:** taking a single-user "first-to-register-becomes-admin" backend
to a multi-user admin-managed RBAC surface without losing existing
deployments.

**Date:** 2026-06-22

**Project:** honcho-inspector-backend (Java 25 / Spring Boot 3.5 /
SQLite / single-jar).

**Status:** Phase 1 of the rollout is shipped (commits `029bea4` through
`a9a5fd5`). 449 tests, 0 failures, 0 errors. Live integration test
against `honcho.cloudbsd.org` confirmed the bootstrap, RBAC, audit
retention, and Honcho fan-out surfaces.

---

## Why this lesson exists

The previous security review of the backend (see `docs/SECURITY.md` v0)
flagged three findings that needed to be closed together: **F-10 (no
audit log)**, **F-11 (`error.include-message: always` in prod)**, and
**F-12 (`isAdmin` is a boolean with no role system)**. Closing F-12 by
itself is trivial; closing it without closing F-10 means the new
admin-only routes have no audit trail. The two are coupled by design.

The rest of the lesson records the design choices and the ones we
would change if we had to do it again.

---

## 1. Declarative gate, not opt-in helper

The temptation is to write a `requireAdmin(HttpServletRequest req)`
helper that each admin controller method calls at the top. We
explicitly did not do that. Instead:

- `@RequireAdmin` annotation on a class or method.
- `AdminAuthInterceptor implements HandlerInterceptor.preHandle`.
- `AdminAuthConfig implements WebMvcConfigurer.addInterceptors` —
  registers the interceptor on `/api/**`.

Why a declarative gate wins:

- **Uniform enforcement.** Every handler in `/api/admin/**` is
  uniformly protected. There is no per-controller call to forget.
- **Visible at definition time.** A reviewer looking at
  `AdminUserController` sees the `@RequireAdmin` annotation on the
  class. They do not have to look for a `requireAdmin(...)` call in
  the method body and verify the helper was not bypassed.
- **Testable in isolation.** `AdminAuthInterceptorTest` has 8 unit
  tests (class-level, method-level, mixed, missing-annotation, missing
  session attribute, non-admin, admin, static-resource passthrough) —
  none of them require a real controller. If we had an opt-in helper
  we'd have to test it through every controller, which is much
  noisier.
- **Order is enforced.** `SessionAuthFilter` (a `OncePerRequestFilter`)
  runs first and sets the `CurrentUser` request attribute. The
  `AdminAuthInterceptor` runs second and reads it. The two are layered
  in the filter chain and there is no overlap.

The drift check (`@AdminAuthInterceptor` only matches
`HandlerMethod`) means static resources, the Springdoc surface, and
non-handler paths pass through with zero overhead.

## 2. Self-protection in the service, not the controller

A subtle bug: if the self-protection rules (last-admin cannot be
demoted or deleted; a user cannot demote or delete themselves) are
enforced in the controller layer, then a future endpoint that
deletes users via a different path bypasses them. We put the rules
in `AdminUserService.update` / `AdminUserService.delete`. Every
controller that uses those methods inherits the rules for free.

The error code distinction is also service-side: `ErrorKind.VALIDATION`
→ 400, `ErrorKind.CONFLICT` → 409. The controller just maps the
`UpdateResult` / `DeleteResult` to HTTP. The service decides the
semantics; the controller decides the transport.

## 3. Audit is fire-and-forget, not transactional

`AdminAudit.record(...)` wraps `AuditLogDao.insert` in a try/catch
that logs the failure at WARN and swallows it. The calling write
path (user create, user delete, password reset, etc.) is never
broken by a broken audit table.

This is a deliberate trade. The alternative — making audit insertion
transactional with the mutation — is the "right" design from a
security-engineering textbook, but it has two practical problems:

1. The audit insert becomes a hard dependency. A bad migration that
   drops the `audit_log.metadata` column would block all user CRUD
   across the whole deployment, even for non-admins.
2. It muddies the failure model. A failed user create because the
   audit insert failed returns 500 to the UI; the operator cannot
   tell whether the user was created or not.

The chosen design — fire-and-forget — preserves the loud-failure
property (a 500 still means the mutation did not happen) while
decoupling the audit table's health from the user table's. A WARN in
the JSONL log is enough; SIEMs can ingest the log and alert on
`WARN` events with `logger=AdminAudit`.

The trade only works because **all the actually-critical
audit-relevant data is also in the request itself** (actor session
id, target user id, action, ip, request body). The audit table is
the *index* of who did what, not the source of truth. The
authoritative log is still the JSONL stream with the
`MaskingJsonGeneratorDecorator` scrubbing keys/tokens.

## 4. Audit retention: both criteria, not just one

`honcho.audit.retention-days=90` AND `honcho.audit.max-rows=1000000`.
The job (`AuditRetentionJob.run`) deletes rows older than 90 days
AND, if the table still exceeds 1M rows, deletes the oldest
remaining rows until it fits.

A "just age" retention on a low-traffic install works fine. A "just
size" retention on a high-traffic install works fine. Both fail on
the corners: a long-running low-traffic install will keep decades of
small-volume rows; a short bursty install will keep the burst
forever. The OR-combine is the only one that handles both.

The job is gated: it audits its own runs, but only when
`totalDeleted > 0`. A no-op run does not produce an `audit.purge`
row. This avoids daily noise that would otherwise drown out the
actionable events in `GET /api/admin/audit?action=audit.purge`.

## 5. The bootstrap pattern

`AdminBootstrap` is an `@EventListener(ApplicationReadyEvent)` that
reads `honcho.bootstrap.*` from the drop-in config and creates the
first admin **only if `users.count() == 0`**. The bundled jar ships
the block blank; production opt-in is by populating the config.

The pattern's three properties matter:

- **Fail-safe in dev.** A developer running the bundled jar on a
  fresh DB gets an empty admin user table and a one-line WARN log.
  No admin is silently created. No surprise accounts in test
  environments.
- **Recoverable.** If the admin loses their password, set
  `is_admin = 1` on an existing user via `sqlite3` directly — no
  bootstrap required, no config edit required. The bootstrap is
  not the only recovery path.
- **Auditable.** The bootstrap path records a `user.bootstrap`
  audit entry with `actor_user_id = NULL` and `session_id = NULL`.
  Any audit query that filters for non-NULL actors will skip the
  bootstrap row; a query that filters for `action = 'user.bootstrap'`
  will find it. The asymmetric `actorUserId` is intentional — it
  distinguishes "the system did this" from "a user did this".

The config file in `etc/honcho-inspector/application.yml.example` has
a 5-step "production safety checklist" at the top that includes
"remove the bootstrap credentials from this file once the first
admin exists — leaving them in is a credential-on-disk leak". The
checklist is the actual security boundary; the bootstrap code is
the mechanism.

## 6. `/api/health` is a liveness probe, not an enumeration API

The previous `/api/health` returned `{ok, users, sessions, profiles,
needs_register}`. We removed the three counts because they are an
unauthenticated enumeration of the user base — anyone who can reach
the service can read how many users are on it.

The counts moved to `/api/admin/dashboard/overview` (admin-only).
`/api/health` now returns only `{ok, needs_register}`. The UI uses
`needs_register` to decide whether to render a "create your first
admin" prompt or a "log in" prompt on a fresh deployment.

This is a small change with a big security win. The operator's
README, the OpenAPI spec, and the SECURITY.md are all updated to
reflect the new shape.

## 7. The OpenAPI drift check is intentionally narrow

`OpenApiDriftCheckTest` compares **paths, methods, and operationIds**
between `docs/openapi.yaml` (hand-written narrative) and
`docs/openapi.generated.json` (springdoc snapshot). It does NOT
compare schemas, parameters, requestBody, responses, or
descriptions.

This narrowness is the whole point. The hand-written file is
allowed to be richer — it can have detailed descriptions, examples,
a `x-workflow-narrative` extension, and admin schemas that are
documented for code generators but not actually emitted by springdoc
(like `AuditEntry` and `AuthSession`, which the controllers return
as anon-style inline objects). The drift check would be a constant
source of false positives if it tried to be deeper.

The cost: the hand-written file can drift from the live
implementation. We mitigate that by having the hand-written
descriptions refer back to the implementation files
(`AdminUserService.java`, `AdminBootstrap.java`, etc.), and by
running the test suite which exercises the actual routes.

## 8. The man page is operator-facing, not developer-facing

The README is the developer entry point. The SECURITY.md is the
auditor / reviewer entry point. The man page is the **operator**
entry point — the person who has to deploy, configure, and run the
service, and who does not necessarily read the codebase.

The man page has:

- `NAME` and `SYNOPSIS` on the standard one-liner.
- `DESCRIPTION` — what the service is, in two paragraphs.
- `OPTIONS` — only the launcher-script flags (the rest is env vars).
- `ENVIRONMENT` — every env var, with default and effect.
- `CONFIGURATION FILES` — the drop-in yaml location per OS.
- `FILES` — the actual on-disk layout (`logs/`, DB, config).
- `FIRST STARTUP` — the bootstrap procedure, end-to-end.
- `ADMIN API` — the high-level surface, with the self-protection
  rules called out.
- `AUDIT LOG AND RETENTION` — the retention policy, the cron, the
  manual trigger.
- `RECOVERY FROM LOST ADMIN` — the sqlite3 escape hatch.
- `EXIT STATUS` — the launcher's exit codes.
- `SIGNALS` — SIGTERM handling.
- `SEE ALSO` — the related docs and the upstream Honcho docs.
- `BUGS` — the known limitations (no rate limit, no rate limit
  on login).

The man page is the only doc that follows the FHS convention
(installable to `/usr/local/share/man/man1/`). The README and
SECURITY.md are repo-internal.

## 9. YAML gotchas, learned the hard way

Two distinct YAML gotchas surfaced during the docs update:

1. **Unquoted colons in summary values.** `summary: Current state:
   row counts` parses the second colon as a new mapping key. Fix:
   `summary: 'Current state: row counts'`. The OpenAPI spec is
   full of natural-language `summary` fields that include colons
   ("Returns: ok", "Self-protection: 409"); every one needs to be
   quoted. The `Edit` tool's `oldString` matching does not catch
   this — the YAML parses with a different structure than the
   hand-written text suggests.

2. **The `Edit` tool fails on multi-line YAML folded scalars.**
   Hand-written OpenAPI files use a single long line for the
   `x-workflow-narrative` value, with embedded `\n` escapes and
   `  \ ` continuation markers. The `Edit` tool's `oldString`
   comparison does not handle these reliably because of how the
   read tool normalizes whitespace. Workaround: when the edit
   target is a folded scalar (or a description field with hard
   line breaks), use `python3` with `text.replace(old, new, 1)`
   on `Path("file").read_text()`.

These are not bugs in our process; they are properties of the
YAML spec interacting with the tooling. They are also not unique
to us — anyone maintaining a hand-written OpenAPI file will hit
them eventually.

## 10. What we would do differently

- **Self-protection tests were spread across the service unit
  tests and the controller MockMvc tests.** Both layers test
  "the last admin cannot be demoted" — once at the unit level
  (return value), once at the HTTP level (409). The
  controller-level test is enough; the service-level test is
  redundant given the controller test. We left both for clarity,
  but a future cleanup could remove the service-level one and
  save ~30 lines.

- **The audit retention job is `@Scheduled`, not a
  `CommandLineRunner`.** `@Scheduled` requires `@EnableScheduling`
  on the application class, which means test fixtures that boot
  the full context have the schedule armed. We've worked around
  it by giving the job an explicit `run(actorId, sessionId)`
  method that the maintenance controller calls synchronously, but
  a future refactor could move the retention to a `CommandLineRunner`
  (or a Spring Integration `poller`) and let the maintenance
  controller call the same code path explicitly.

- **The drift check does not check tags.** We could have a
  hand-written path tagged `admin` that the live snapshot tags
  `auth`, and the test would not catch it. Adding a tag-comparison
  pass to `OpenApiDriftCheckTest` would close that gap. We did
  not do it because the existing hand-written tags are stable
  and the cost of a false positive (legitimate but mismatched
  tags) is high.

## 11. Related work, in this repo

- `src/main/java/com/revytechinc/honchoinspector/auth/RequireAdmin.java`
  — the annotation.
- `src/main/java/com/revytechinc/honchoinspector/auth/AdminAuthInterceptor.java`
  — the gate.
- `src/main/java/com/revytechinc/honchoinspector/auth/AdminUserService.java`
  — the self-protection enforcement.
- `src/main/java/com/revytechinc/honchoinspector/auth/AuditRetentionJob.java`
  — the daily sweep.
- `src/main/java/com/revytechinc/honchoinspector/auth/AdminBootstrap.java`
  — the first-admin path.
- `src/main/java/com/revytechinc/honchoinspector/auth/AdminDashboardService.java`
  — the parallel Honcho fan-out.
- `src/main/java/com/revytechinc/honchoinspector/auth/AdminMaintenanceController.java`
  — the manual purge and session-sweep.
- `src/main/java/com/revytechinc/honchoinspector/auth/AdminAuthInterceptorTest.java`
  — the 8 unit tests on the gate.
- `src/main/java/com/revytechinc/honchoinspector/auth/AdminUserServiceTest.java`
  — the 23 unit tests on the self-protection rules.
- `etc/honcho-inspector/application.yml.example`
  — the operator-facing drop-in template.
- `docs/honcho-inspector.1`
  — the operator-facing groff man page.
