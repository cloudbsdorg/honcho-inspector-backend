# Lesson: Admin RBAC rollout on a Spring Boot service

**Topic:** taking a single-user "first-to-register-becomes-admin" backend
to a multi-user admin-managed RBAC surface without losing existing
deployments.

**Date:** 2026-06-22

**Project:** honcho-inspector-backend (Java 25 / Spring Boot 3.5 /
SQLite / single-jar).

**Status:** Phase 1 of the rollout is shipped (commits `029bea4` through
`a9a5fd5`). Phase 2 added the emergency-action CLI (`CliRunner`) and
the first-run API (`SetupController`). 487 tests, 0 failures, 0 errors.
Live integration test against `honcho.cloudbsd.org`, `minimini.cloudbsd.org`
(macOS), and `pppoe1.cloudbsd.org` (FreeBSD) confirmed the bootstrap,
RBAC, audit retention, Honcho fan-out, first-run mode, and CLI surfaces.

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
- `packaging/homebrew/honcho-inspector.rb`
  — the Homebrew tap formula (macOS + Linuxbrew).
- `packaging/linux/README.md`
  — Linux packaging notes (Homebrew/Linuxbrew + .deb/.rpm recipes).

## 12. macOS installer live-test findings (2026-06-21)

Live-tested on `minimini.cloudbsd.org` (macOS 26.5 Tahoe, arm64, JDK
25+36, sudo unlocked). The POSIX install script, launchd plist, and
launcher all worked end-to-end. Seven new lessons from the run:

### 12.1 `when_authorized` is not a valid Spring Boot 3.5 enum value

The `etc/honcho-inspector/application.yml.example` template had
`server.error.include-message: when_authorized`, which I had taken
from a doc snippet. Spring Boot 3.5's `ErrorProperties.IncludeAttribute`
enum accepts only `ALWAYS`, `NEVER`, `ON_PARAM`. Anything else causes
the `ApplicationContext` to fail to start with
`failed to convert java.lang.String to ... IncludeAttribute
(caused by IllegalArgumentException: No enum constant ...
when_authorized)`. The fix is `include-message: never` — the only
safe value for prod. The launcher still comes up enough to write the
JSONL log of the failure, which is what made the bug diagnosable from
the log directory alone. **Lesson:** when writing a config template
that references framework enums, validate the values against the
actual framework version, not a doc snippet.

### 12.2 The launchd plist must set `HONCHO_DB_PATH` to an absolute path

On macOS, the launchd working directory is `/` (a SIP-protected
read-only filesystem). The bundled `application.yml` has
`url: ${HONCHO_DB_PATH:jdbc:sqlite:${HONCHO_DB_FILE:honcho-inspector.db}}`
— a relative path. The first run failed with
`opening db: 'honcho-inspector.db': Read-only file system`. The fix
is to set `HONCHO_DB_PATH` to an absolute path in the plist's
`EnvironmentVariables`:

```xml
<key>EnvironmentVariables</key>
<dict>
    <key>HONCHO_CONFIG_DIR</key>
    <string>/usr/local/etc/honcho-inspector</string>
    <key>HONCHO_DB_PATH</key>
    <string>jdbc:sqlite:/var/lib/honcho-inspector/honcho-inspector.db</string>
</dict>
```

The same fix is needed in the systemd unit (`Environment=HONCHO_DB_PATH=...`)
and the rc.d script (set the env var before `command` is invoked).
**Lesson:** the framework's "default to a relative path" is a dev-mode
convenience. In any daemon-managed context, the working directory is
untrusted and may be read-only or wiped; always set the data path
absolutely via the init system's env-file mechanism.

### 12.3 The launchd plist should call the launcher, not `java` directly

The first version of the plist hardcoded `/usr/local/opt/openjdk/bin/java`
because that's the Homebrew install path for OpenJDK. But the operator
may have JDK 25 installed via the macOS installer (`/Library/Java/JavaVirtualMachines/jdk-25.jdk/...`)
or a manual tarball — neither of which puts java at that exact path.
The fix is to have the plist call the launcher script, which uses
`java` from the PATH (or `JAVA_HOME` if set):

```xml
<key>ProgramArguments</key>
<array>
    <string>/usr/local/bin/honcho-inspector</string>
</array>
```

The launcher handles the variable Java install. **Lesson:** the plist
is the wrong place to encode a Java path. Keep the init file's
`ProgramArguments` to a thin shim that delegates to a launcher.

### 12.4 `RollingFileAppender` does NOT auto-create parent directories

The logback config writes JSONL logs to `${HONCHO_CONFIG_DIR}/logs/honcho-inspector.jsonl`.
On a fresh install, that `logs/` subdir doesn't exist. Logback's
`RollingFileAppender` does not create parent dirs automatically; the
install must. The fix is a single line in `create_dirs()`:

```bash
install -d -m 0750 -o "$SERVICE_USER" -g "$SERVICE_GROUP" "$CONFIG_DIR/logs"
```

**Lesson:** any directory the framework writes to as a side-effect
must be enumerated in the install script. Don't trust the framework
to mkdir for you.

### 12.5 The launcher must probe `/usr/local/lib/` for the manual-install jar

The launcher's first version only probed `$PROJECT_DIR/target` and
`$HOMEBREW_PREFIX/opt/honcho-inspector/libexec`. But the POSIX install
script copies the jar to `/usr/local/lib/honcho-inspector/`. The fix
is a three-tier probe in the launcher:

```bash
JAR_DIR="${HONCHO_JAR_DIR:-}"
if [ -z "$JAR_DIR" ] && [ -n "${HOMEBREW_PREFIX:-}" ] \
    && [ -d "${HOMEBREW_PREFIX}/opt/honcho-inspector/libexec" ]; then
    JAR_DIR="${HOMEBREW_PREFIX}/opt/honcho-inspector/libexec"
fi
if [ -z "$JAR_DIR" ] && [ -d /usr/local/lib/honcho-inspector ]; then
    JAR_DIR=/usr/local/lib/honcho-inspector
fi
if [ -z "$JAR_DIR" ]; then
    JAR_DIR="$PROJECT_DIR/target"  # dev mode
fi
```

**Lesson:** the same launcher must serve three install paths (Homebrew,
manual, dev). Probe them in order of specificity, fallback to the
dev path last.

### 12.6 The install script needs a `find_source_file` helper

The install script needs to find source files (the jar, the plist,
the man page, the application.yml template) in a few possible
locations depending on how the operator runs it. The first version
hardcoded `$PROJECT_DIR/etc/...` and `$PROJECT_DIR/docs/...`, which
broke when the script was copied to `/tmp/install-honcho-inspector`
and run from there. The fix is a small helper that probes four
locations and also falls back to a flat layout (basename only):

```bash
find_source_file() {
    local relpath="$1"
    local base="$(basename "$relpath")"
    for DIR in "$SCRIPT_DIR/.." "$SCRIPT_DIR" "/tmp" "$PWD"; do
        [ -z "$DIR" ] && continue
        if [ -f "$DIR/$relpath" ]; then echo "$DIR/$relpath"; return 0; fi
        if [ "$base" != "$relpath" ] && [ -f "$DIR/$base" ]; then
            echo "$DIR/$base"; return 0
        fi
    done
    return 1
}
```

**Lesson:** any installer that supports both "run from the source
tree" and "copy to /tmp/ and run" needs a source-file probe, not a
hardcoded relative path.

### 12.7 macOS `/tmp` is a symlink to `/private/tmp` with TCC restrictions

On macOS, `/tmp` is a symlink to `/private/tmp`, which is gated by
macOS's TCC (Transparency, Consent, and Control) subsystem. The
unprivileged user cannot write to `/tmp` from a remote SSH session
even with `sudo` from the same context — the kernel-level
sandboxing blocks the file create. The workaround for the install
test was to use `sudo tee` with the target path being a system path
(`/usr/local/etc/honcho-inspector/application.yml`), or to use
`sudo cat | sudo tee` for content, but not `cp /tmp/foo /etc/bar`
even with `sudo` on the `cp`. **Lesson:** when scripting macOS
remotely, do all writes via `sudo tee` directly to the target path;
do not stage files in `/tmp/`. The `/private/tmp` TCC rule is not
documented clearly and is easy to hit.

---

## 13. First-run mode and the emergency-action CLI

After Phase 1 shipped, two operator-facing gaps surfaced:

1. **First-run with the UI.** The original bootstrap (`AdminBootstrap`)
   reads `honcho.bootstrap.*` from the drop-in config. This works
   for headless operators, but the UI (a separate repo) needed a
   way to bootstrap the very first admin through the API so the
   UI's "create your first admin" wizard could work without any
   pre-existing credentials. The user said: "i want the backend to
   reflect a state of being in a first run mode, have the UI pick
   that up, and continue in the UI."

2. **Emergency actions when the web server is up.** When an admin
   is locked out (forgotten password, lost credentials, the bootstrap
   block was deleted after first use and the single admin was
   demoted), the operator needs a way to act without going through
   the API. The user said: "now we should have something on the jar
   to manage the backend, emergency actions, like reset system
   passwords/tokens."

The first gap became `SetupController` + the `first_run` field in
`HealthResponse`. The second became `CliRunner` (a
`CommandLineRunner` bean dispatching to five subcommands).

### 13.1 First-run mode: two bootstrap paths, one first-admin wins

The bootstrap decision is now three-way, not two-way:

- **Config-file bootstrap** — populate `honcho.bootstrap.*` in
  `${HONCHO_CONFIG_DIR}/application.yml`. `AdminBootstrap` creates
  the admin on `ApplicationReadyEvent` when the `users` table is empty.
- **API-driven bootstrap** — call `POST /api/setup/first-admin` (open
  when the DB is empty, returns a session immediately).
- **CLI bootstrap** — run `honcho-inspector promote-to-admin --username X`
  after creating a user via the CLI or directly via `sqlite3`.

The three paths are mutually exclusive: the first one to insert a row
into `users` wins. Once any user exists, `AdminBootstrap` becomes a
no-op (its check is `users.count() == 0`), and `/api/setup/first-admin`
returns 409.

The `/api/health` response gained a new field: `first_run`. It is
true when `users.count() == 0`. The previous `needs_register` field
is kept as an alias of `first_run` (same value, different name) so
the old UI contract is preserved while new code can use the more
descriptive name.

### 13.2 `first_run` not `isFirstUser` — the field name matters

The first draft of the new field was `is_first_user`, with the
understanding that "first user" implied "first run." But the user
specifically asked for "first run mode" — not "first user mode." The
naming distinction matters because "first user" is a sequence
question (is this the first user?) while "first run" is a state
question (is the service in the bootstrap state?). They mean the
same thing right now (no users + first run), but they might diverge
in the future (e.g. an operator who wants to wipe users but not
config). The chosen name (`first_run`) is the state, not the count.

`needs_register` is kept as an alias because the UI was already
using it before the first-run concept was formalised. Keeping the
alias preserves the wire-contract while letting the code use the
more accurate term.

### 13.3 CLI dispatch must happen in `main()`, not `CommandLineRunner`

The first version of `CliRunner` was a `CommandLineRunner`. It
worked for the simple case — `honcho-inspector list-users` would
intercept the first argument, dispatch, and `System.exit(0)`. But
it broke in two ways:

1. **Spring Boot binds the web server first.** If the operator runs
   `honcho-inspector reset-admin-password --generate` while the
   service is up, the web server starts binding to port 8080 (or
   fails with "address in use"), THEN the CLI runs. This is wrong:
   the CLI should not start the web server.

2. **Schema migrations run too late.** A `CommandLineRunner` runs
   after the `ApplicationContext` is fully refreshed, which is after
   schema migrations. If a CLI command needs to run against a
   pre-migration DB (e.g. to fix a migration that broke), it cannot.

The fix is to dispatch in `main()` BEFORE `SpringApplication.run()`:

```java
public static void main(String[] args) {
    System.exit(dispatch(args));
}

static int dispatch(String[] args) {
    if (shouldRunCli(args)) {
        return runCli(args);  // boots minimal Spring context, runs CLI, returns exit code
    }
    return SpringApplication.run(DashboardApplication.class, args) != null ? 0 : 1;
}
```

`shouldRunCli(String[])` is a tiny predicate:
```java
static boolean shouldRunCli(String[] args) {
    return args.length > 0 && CliRunner.isKnownCommand(args[0]);
}
```

`runCli(String[])` boots a minimal Spring context using
`SpringApplicationBuilder.web(WebApplicationType.NONE)` so the web
server doesn't start. The CLI bean is autowired by the context, the
operator's subcommand runs, and the exit code propagates up to
`System.exit` via `main()`.

**The exit-code propagation is important.** A `CommandLineRunner`
that calls `System.exit(int)` works, but only because the
`ApplicationRunner.run` throws an `ExitException` that Spring
swallows. Putting `System.exit` in `main()` is the only way to be
sure the exit code is honoured across all paths (early dispatch,
CLI dispatch, normal Spring startup).

### 13.4 The `shouldRunCli` predicate is testable without Spring

`DashboardApplicationMainDispatchTest` has 7 unit tests on the
`shouldRunCli` predicate. They run in 0.11s (no Spring context
bootstrap), which is fast enough to keep in the inner dev loop. The
full CLI integration tests live in `CliRunnerTest` (23 tests) and
exercise the real Spring context.

The unit-vs-integration split mirrors the lesson's separation of
"what does the dispatch predicate say?" from "what does the CLI
actually do?" The predicate is the smaller, faster, more critical
piece (it's the entry point). The full flow is bigger, slower, and
already covered by the integration tests.

### 13.5 `CliRunner.handle(String, String[])` is public for cross-package dispatch

The CLI's main entry point is `CommandLineRunner.run(String...)`,
but `main()` calls `handle(cmd, args)` directly (so the exit code
can return to `main()` without `System.exit` in the middle of the
call stack). `handle` is `public` (not `protected`) for this
reason: it's called from a different package (`com.revytechinc.honchoinspector`
calls `com.revytechinc.honchoinspector.cli.CliRunner.handle`).

The convention "public for cross-package dispatch, package-private
otherwise" is documented in a one-line comment in the source. The
next reader does not have to guess why the method is `public`.

### 13.6 CLI exit codes follow `sysexits.h` conventions

| Code | Meaning              | Maps to                              |
|------|----------------------|--------------------------------------|
| 0    | success              | EX_OK                                |
| 2    | invalid arguments    | EX_USAGE                             |
| 3    | user not found       | EX_NOUSER (3 on BSD, ENOENT on Linux)|
| 4    | validation error     | EX_DATAERR                           |
| 5    | database error       | EX_IOERR                             |

These match the FreeBSD `sysexits.h` convention closely enough
that shell scripts can branch on them without a translation table.
The codes 1 and 2 are reserved (Spring Boot's `SpringApplication.run`
uses 1 for startup failure; the launcher script's "java crashed"
exit code is 1). Codes 6+ are reserved for future CLI commands
(revoke-all-sessions doesn't use them yet, but a planned
`rotate-crypto-key` command will use EX_NOPERM = 77 for "caller has
no access" and EX_CONFIG = 78 for "crypto key not yet in env file").

### 13.7 Related work, this lesson

- `src/main/java/com/revytechinc/honchoinspector/DashboardApplication.java`
  — `main()` calls `System.exit(dispatch(args))`; `shouldRunCli(String[])`
  predicate; `dispatch(String[])` boots minimal context
  (`WebApplicationType.NONE`) for CLI; `runCli(String[])` returns exit code.
- `src/test/java/com/revytechinc/honchoinspector/DashboardApplicationMainDispatchTest.java`
  — 7 unit tests on `shouldRunCli` predicate. Fast (0.11s), no
  Spring context bootstrap.
- `src/main/java/com/revytechinc/honchoinspector/cli/CliRunner.java`
  — emergency-action CLI (list-users, reset-admin-password,
  promote-to-admin, revoke-all-sessions, help). Exit codes 0/2/3/4/5.
  `handle(String, String[])` is public for cross-package dispatch.
- `src/test/java/com/revytechinc/honchoinspector/cli/CliRunnerTest.java`
  — 23 unit/integration tests.
- `src/main/java/com/revytechinc/honchoinspector/auth/SetupController.java`
  — `POST /api/setup/first-admin` (open when DB empty, returns
  `LoginResponse`).
- `src/test/java/com/revytechinc/honchoinspector/auth/SetupControllerTest.java`
  — 8 tests for first-run mode.
- `src/main/java/com/revytechinc/honchoinspector/auth/AuthController.java`
  — `/api/health` returns `{ok, first_run, needs_register}`.
- `src/main/java/com/revytechinc/honchoinspector/config/OpenApiConfig.java`
  — `TAG_SETUP` added.
- `src/main/java/com/revytechinc/honchoinspector/filter/SessionAuthFilter.java`
  — `PUBLIC_PATHS` includes `/api/setup/first-admin`.
- `docs/honcho-inspector.1` — man page documents the CLI in the new
  `EMERGENCY ACTIONS` section, the first-run flow in `FIRST STARTUP`,
  and the new exit codes in `EXIT STATUS`.

For the operator-facing packaging lessons (launcher env file
sourcing, FreeBSD rc.d naming convention, Makefile portability,
meta-port design, etc.) see the companion lesson
[`installer-and-packaging.md`](installer-and-packaging.md).
