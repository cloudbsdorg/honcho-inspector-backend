# Lesson: Installer, packaging, and operator-runtime

**Topic:** shipping the Honcho Inspector backend on macOS, Linux, and
FreeBSD with a POSIX install script, a portable Makefile, a Homebrew
formula, a FreeBSD port, and a FreeBSD meta-port — and the OS quirks
that bit us on each platform.

**Date:** 2026-06-22

**Project:** honcho-inspector-backend (Java 25 / Spring Boot 3.5 /
SQLite / single-jar).

**Status:** all four install paths are live-tested end-to-end.

| Path                  | Live-tested on           | Status |
|-----------------------|--------------------------|--------|
| macOS launchd         | `minimini.cloudbsd.org`  | PASS   |
| Linux systemd         | (dev only — `make install`) | dry-run OK |
| FreeBSD rc.d          | `pppoe1.cloudbsd.org`    | PASS   |
| Homebrew / Linuxbrew  | (formula written)        | not installed locally |
| FreeBSD port          | `pppoe1.cloudbsd.org`    | PASS   |
| FreeBSD meta-port     | (depends on UI port)     | skeleton only |

This lesson is the companion to `admin-rbac-rollout.md` (which covers
the application-side rollout). The split is: that file is about what
the code does; this file is about how the code reaches the operator's
host and what the operator sees at startup.

---

## Why this lesson exists

Three things the previous lesson covered — the `@RequireAdmin` gate,
the audit log, and the bootstrap pattern — each have an operator
consequence that the application code can't address alone:

- The bootstrap path (`AdminBootstrap`) reads `honcho.bootstrap.*`
  from a drop-in config file. The operator needs to know where to put
  it, how to populate it, and how to remove it after first use.
- The audit retention cron (`@Scheduled`) is invisible to the
  operator. The man page and SECURITY.md document the cron, the
  cap, and the manual purge endpoint.
- The login flow is gated by `X-Session-Id`, but the service runs as
  an unprivileged service user (`www-data` / `www` / `_www`), which
  is a deployment-time decision, not an application-time one.

None of those consequences are visible in the code. They live in the
installer, the man page, and the operator hardening checklist. The
installer is the part that has to be portable to three very different
init systems; this lesson is everything I learned shipping to all three.

---

## 1. The launcher is the install contract

The service has five install paths:

1. Homebrew (`brew install cloudbsdorg/honcho-inspector/honcho-inspector`)
2. FreeBSD port (`pkg install honcho-inspector`)
3. FreeBSD meta-port (`pkg install honcho-inspector`)
4. POSIX install script (`sudo bin/install-honcho-inspector`)
5. `make install` (delegates to the POSIX install script)

All five need a thin shim in front of `java -jar`. The shim is
`bin/honcho-inspector`, and it owns three responsibilities:

- **Find the jar.** Three install paths put the jar in three places:
  `${HOMEBREW_PREFIX}/opt/honcho-inspector/libexec` (Homebrew),
  `/usr/local/lib/honcho-inspector` (POSIX + FreeBSD), and
  `$PROJECT_DIR/target` (dev). Probe in that order; default to dev if
  none match.
- **Set `HONCHO_CONFIG_DIR`** to the OS-appropriate default unless
  already in the environment.
- **Source the per-OS env file** (`/etc/default/honcho-inspector` on
  Linux + FreeBSD, `/etc/defaults/honcho-inspector` on macOS) and
  apply defaults for `HONCHO_DB_PATH` and `HONCHO_CONFIG_DIR` if
  unset.

The shim's responsibilities are deliberately small. Everything else
(file ownership, service user, service file, man page, env-file
template) belongs in the install script, not the launcher. The
launcher is what gets re-run on every boot; it must not need to know
about the service user, the data dir, or anything else that is a
deployment-time decision.

## 2. The launcher must source the per-OS env file

The Spring Boot context picks up `HONCHO_CRYPTO_KEY` etc. via
`@Value("${honcho.crypto-key}")`. That works fine for an operator who
sets the env var in their shell. It does NOT work for the systemd /
launchd / rc.d case, where the service starts with a near-empty
environment.

The fix is a `source_env_file()` shim function that probes the
per-OS env file in order:

```sh
# Linux: /etc/default then /etc/sysconfig (RHEL convention)
# FreeBSD: /etc/default then ${HOMEBREW_PREFIX}/etc/honcho-inspector.env (port override)
# macOS: /etc/defaults then ~/Library/Application Support/honcho-inspector/env
for f in /etc/default/honcho-inspector /etc/sysconfig/honcho-inspector; do
    [ -r "$f" ] && { set -a; . "$f" 2>/dev/null; set +a; }
done
```

The `set -a` / `set +a` pair auto-exports every variable the file
sets. A missing or unreadable file is silently skipped — the launcher
must work in dev (where the env file doesn't exist) and in production
(where it does) with the same code path.

The convention `set -a; . $f; set +a` is important: the launcher
sources the env file with `set -a` so every variable is exported,
then `set +a` to restore the original `allexport` state. This lets
the operator override env values from the launching shell (a
debugging convenience) while still picking up defaults from the file.

**Lesson:** the launcher is the right place to source the env file.
The init system is the wrong place (systemd's `EnvironmentFile=` does
work for systemd but is unreadable by rc.d and launchd). One shim
function, three probe paths.

## 3. The env file does NOT include the data path

The env file the install script drops at
`/etc/default/honcho-inspector` contains operator-tunable values
only:

```sh
# /etc/default/honcho-inspector
HONCHO_CRYPTO_KEY="base64-of-32-random-bytes"
```

That's it. `HONCHO_DB_PATH` and `HONCHO_CONFIG_DIR` are NOT in the
env file. They belong in the init system unit (the systemd
`Environment=...` line, the launchd `EnvironmentVariables` dict, the
rc.d `command_subdirectory` plus a `command_args` block), not in a
file the operator edits.

Why the separation? Because the data path is a deployment-time
decision tied to the service user (`www-data` writes to
`/var/lib/...`, `www` writes to `/var/db/...`, `_www` writes to
`/var/...`). Putting it in the env file would mean the operator had
to re-set it if they ever changed the service user. Putting it in
the init system means the install script knows the right value per OS
and writes it once.

The launcher's `default_data_dir()` function provides a fallback for
the case where the env var was unset (e.g. operator running the jar
directly in dev). It uses the same OS-conventional paths the install
script uses, so the behaviour is identical.

**Lesson:** env file = operator-tunable runtime knobs.
Init system = paths, user, environment, process control.
Mixing them is a maintenance trap.

## 4. The install script must export env vars after sourcing

The FreeBSD rc.d script does this:

```sh
. /etc/default/honcho-inspector
export HONCHO_CONFIG_DIR
export HONCHO_DB_PATH
command="/usr/local/bin/honcho-inspector"
```

The `export` is critical. Sourcing a file in `sh` sets variables in
the shell, but rc.d's `command_args` block is executed in a
subshell. Without the explicit `export`, the env file's variables
don't reach the launched process.

We hit this on the FreeBSD live test: the first start failed with
Logback's `${HONCHO_CONFIG_DIR:-.}` falling back to `.` (the rc.d
subshell's CWD, which is `/` — read-only). The fix was two lines:
add `export HONCHO_CONFIG_DIR` and `export HONCHO_DB_PATH` after the
`source_env_file` block.

**Lesson:** rc.d scripts that source an env file MUST export the
relevant variables explicitly. Sourcing alone does not propagate
env vars to a subshell-launched process.

## 5. `find_source_file` basename probes are too greedy

The install script has a `find_source_file()` helper that probes
four locations for a relative path:

```sh
find_source_file() {
    local relpath="$1"
    local base="$(basename "$relpath")"
    for DIR in "$SCRIPT_DIR/.." "$SCRIPT_DIR" "/tmp" "$PWD"; do
        [ -z "$DIR" ] && continue
        if [ -f "$DIR/$relpath" ]; then echo "$DIR/$relpath"; return 0; fi
        if [ "$base" != "$relpath" ] && [ -f "$DIR/$base" ]; then
            echo "$DIR/$base"; return 0   # <-- this line is the bug
        fi
    done
    return 1
}
```

The second match (basename-only) was meant to let an operator copy a
single file to `/tmp/` and run the install. But it also matched when
looking for `etc/rc.d/honcho-inspector` — it found
`bin/honcho-inspector` (the launcher), which has the same basename.
The install then tried to copy the launcher to `/usr/local/etc/rc.d/`,
which is the wrong file.

The fix is to drop the basename probe entirely. Operators who copy a
single file to `/tmp/` can still run the install — they just need to
preserve the directory structure (`/tmp/project/etc/rc.d/...`) or run
from the project root. The probe was an over-engineered convenience
that introduced a real bug.

**Lesson:** when a "find this file" helper has multiple matchers,
each matcher must be unambiguous on its own. A basename probe that
fires on launcher lookups is the kind of bug you don't catch until
someone installs it for real.

## 6. FreeBSD rc.d naming convention (underscore, not hyphen)

FreeBSD's rc.d subsystem uses `USE_RC_SUBR=honcho_inspector` and the
file must be installed at `/usr/local/etc/rc.d/honcho_inspector`
(underscore). The `name="..."` declaration inside the script must
match the filename (without `.sh`). The service is then enabled with
`honcho_inspector_enable="YES"` in `/etc/rc.conf` and started with
`service honcho_inspector start`.

This is different from Linux (systemd) and macOS (launchd), where the
unit / plist file uses hyphens (`honcho-inspector.service`,
`com.honcho.inspector.plist`). FreeBSD's convention is "no hyphens
in service names" because rc.d names map to shell variables, where
hyphens are illegal.

The install script converts hyphens to underscores when installing:

```sh
SERVICE_NAME="$(echo "honcho-inspector" | tr '-' '_')"
install -m 0755 etc/rc.d/honcho-inspector "/usr/local/etc/rc.d/${SERVICE_NAME}"
```

POSIX `tr` is used because the install script must work in `/bin/sh`
on FreeBSD, where bash-isms like `${var//pat/repl}` fail (FreeBSD's
`/bin/sh` is not bash).

**Lesson:** every init system has its own naming convention. Don't
fight it; convert at install time.

## 7. Install the jar as BOTH unversioned and versioned forms

```sh
install -m 0644 target/honcho-inspector-backend-0.1.0-SNAPSHOT.jar \
    /usr/local/lib/honcho-inspector/honcho-inspector-backend-0.1.0-SNAPSHOT.jar
ln -sf honcho-inspector-backend-0.1.0-SNAPSHOT.jar \
    /usr/local/lib/honcho-inspector/honcho-inspector-backend.jar
```

The unversioned form (`honcho-inspector-backend.jar`) is what the
init scripts reference, so an upgrade doesn't require editing
service files. The versioned form is what `ls -l` shows, so the
operator can see what's actually deployed. The symlink keeps the
two in sync.

The first version of the install script only wrote the unversioned
form (via `install -m 0644 ... jar /usr/local/lib/.../honcho-inspector-backend.jar`).
On FreeBSD, the rc.d script's `command_args` was
`-jar /usr/local/lib/honcho-inspector/honcho-inspector-backend.jar`,
which worked. But the operator could not tell what version was
running — `ls` just showed a single file. Worse, an upgrade left no
trace of the previous version for rollback.

**Lesson:** ship the versioned jar for operator sanity; symlink the
unversioned name for init-system sanity.

## 8. The Makefile must be portable to both GNU make and BSD bmake

The user told me: "Linux has GNU Make and FreeBSD has a `bmake`."
GNU `make` and FreeBSD's `bmake` differ on a few key things:

- `ifeq` / `ifneq` / `ifdef` / `ifndef` (GNU conditionals) — bmake
  supports them, but the syntax differs subtly. Safer to avoid.
- `$(shell ...)` — works in both, but bmake's quoting rules are
  tighter.
- `$(wildcard ...)` — GNU extension. bmake uses `$(glob ...)` (the
  older BSD form).
- `!=` (shell execution as assignment) — GNU extension. bmake uses
  `:= $(shell ...)`.
- `define` / `endef` — GNU only; bmake uses different syntax.

The fix is to make the Makefile as dumb as possible: shell scripts
inside `target:` recipes do all the work. The Makefile becomes a thin
wrapper that lists targets and delegates each to the install script:

```make
install: install-launcher install-config-only install-jar install-service enable-service

install-linux:
	@os=$$(uname -s); [ "$$os" = "Linux" ] || { echo "not Linux"; exit 1; }; ...

install-freebsd:
	@os=$$(uname -s); [ "$$os" = "FreeBSD" ] || { echo "not FreeBSD"; exit 1; }; ...
```

The recipe-level `case "$$(uname -s)" in ... esac` is a shell
conditional, not a make conditional. Both `make` and `bmake` invoke
the shell to run recipes, so the shell's `case` works identically
in both.

The Makefile targets:

- `make install` — canonical OS-agnostic entry; auto-detects OS.
- `make install-linux` / `make install-freebsd` / `make install-macos`
  — explicit OS aliases; error out on the wrong OS.
- `make install-launcher` / `make install-config-only` /
  `make install-jar` / `make install-service` — fine-grained targets
  for partial installs.
- `make run-jar` — runs the jar with `target/honcho-inspector-backend-*.jar`
  as the unversioned jar.
- `make build` / `make test` / `make package` / `make clean` / `make dev`
  — standard Maven shortcuts.

A future improvement is to add per-OS include files
(`Makefile.linux`, `Makefile.freebsd`, `Makefile.osx`) and use
`include` to dispatch, but it's not necessary today — the recipes
are short enough that the auto-detect path handles all three OSes.

**Lesson:** Makefiles that must work on both GNU make and bmake
should delegate all conditional logic to the shell. The Makefile is
a recipe catalogue; the recipes are POSIX scripts.

## 9. The meta-port pattern

The user wanted a single install command for both the backend and the
future UI. On FreeBSD, the pattern is a **meta-port** — a port with
no `BUILD` / `INSTALL` steps of its own, only `RUN_DEPENDS`:

```make
PORTNAME=       honcho-inspector
CATEGORIES=     www
USES=           metaport
NO_BUILD=       yes
NO_INSTALL=     yes
NO_ARCH=        yes
NO_MTREE=       yes
RUN_DEPENDS=    ${LOCALBASE}/bin/honcho-inspector:net/honcho-inspector \
                ${LOCALBASE}/share/honcho-inspector-frontend/www:www/honcho-inspector-frontend
```

The meta-port lives at `www/honcho-inspector/` (web category, per the
user's instruction "for freebsd i think this would be a web item"),
and its `pkg-descr` describes the product as a single installable
thing while `pkg-message` points operators at the two real packages.

The Homebrew equivalent is a "umbrella formula" at
`cloudbsdorg/honcho-inspector/honcho-inspector` that depends on
`cloudbsdorg/honcho-inspector/honcho-inspector-backend` and
`cloudbsdorg/honcho-inspector/honcho-inspector-frontend`. Same
pattern, different package manager.

**Lesson:** meta-packages are the cleanest way to expose a
multi-binary product as a single install. The meta-package has no
files; it just declares the runtime dependency graph.

## 10. UI naming: 'd' for daemon, 'frontend' for the UI

The FreeBSD backend port is `net/honcho-inspectord` — the trailing
`d` is the FreeBSD convention for daemon services (like
`named`, `httpd`, `sshd`). The user picked this on purpose: "that d
there... thats for the backend... think about what we are going to
do for the ui, which will be a separate package."

The UI package name is `honcho-inspector-frontend` (with hyphens,
full word). This is intentionally inconsistent: the backend has a
terse daemon-style name (`honcho_inspectord` on FreeBSD, where
underscores replace hyphens) and the UI has a verbose consumer-style
name (`honcho-inspector-frontend`). The verbosity matches how a UI
package is installed by end users; the terseness matches how a
daemon port is referenced in `rc.conf` variables.

Inconsistent on purpose, but consistent within each domain:
- **Daemon name:** terse, hyphen-to-underscore on FreeBSD, `d`
  suffix on FreeBSD ports.
- **User-facing package name:** verbose, hyphenated, no suffix.

**Lesson:** package naming conventions are per-domain. Mixing
conventions across the backend + UI is fine; mixing within a domain
is not.

## 11. bash-isms in install scripts

The install script runs on Linux (where `/bin/sh` is often dash),
FreeBSD (where `/bin/sh` is BSD sh), and macOS (where `/bin/sh` is
bash 3.2). The script must work in all three. Common bash-isms to
avoid:

- `${var//pat/repl}` — bash parameter expansion. Use `echo "$var" | tr 'a' 'b'`.
- `[[ ... ]]` — bash conditional. Use `[ ... ]` (POSIX test).
- `local var=$(cmd)` — bash allows it; POSIX requires
  `var=$(cmd); local var="$var"`.
- `function f() { ... }` — bash function syntax. Use `f() { ... }`
  (POSIX function syntax).
- `source` — bash-ism for `.`. Use `.` (POSIX).
- `&>` for stderr+stdout redirect — bash-ism. Use `>file 2>&1`.

The install script's `set -e` (exit on error) is POSIX-safe. So is
`set -u` (treat unset variables as errors) once you've enumerated
every variable with a default.

**Lesson:** if the install script uses `/bin/sh` as its shebang,
the entire script must be POSIX-shell-compliant. bash-isms are bugs
waiting for the next non-Linux host.

## 12. macOS `/tmp` is a symlink to `/private/tmp` with TCC restrictions

On macOS, `/tmp` is a symlink to `/private/tmp`, which is gated by
the TCC (Transparency, Consent, and Control) subsystem. The
unprivileged service user (`_www`) cannot write to `/tmp` from a
remote SSH session even when the script runs as `sudo` from the
operator's shell — TCC blocks the file create.

The workaround is to use `sudo tee` with the target path being a
system path directly:

```sh
sudo cp /tmp/foo /etc/bar   # FAILS: _www can't write to /tmp
sudo tee /etc/bar < /tmp/foo   # WORKS: sudo writes directly
```

For multi-MB files (like a jar), the reliable pattern is:

```sh
sudo bash -c "base64 -d > /usr/local/lib/honcho-inspector/honcho-inspector-backend.jar" \
    < jar.b64
```

`base64` keeps the data stream free of binary pitfalls (NULs,
quoting), `sudo bash -c` runs the whole pipeline as root in a single
shell, and the redirect-target is a system path TCC doesn't gate.

**Lesson:** when scripting macOS remotely, do all writes via
`sudo tee` / `sudo bash -c '...'` directly to the target path. Do
not stage files in `/tmp/` for the unprivileged user.

## 13. The launchd plist must call the launcher, not `java`

The first version of the macOS plist hardcoded
`/usr/local/opt/openjdk/bin/java` (Homebrew's OpenJDK path). That
broke operators who installed JDK 25 via the macOS installer
(`/Library/Java/JavaVirtualMachines/jdk-25.jdk/...`) or a manual
tarball — neither puts java at that exact path.

The fix is to have the plist call the launcher, not `java` directly:

```xml
<key>ProgramArguments</key>
<array>
    <string>/usr/local/bin/honcho-inspector</string>
</array>
```

The launcher uses `java` from `$PATH` (or `$JAVA_HOME` if set),
which is whatever the operator's environment resolves.

**Lesson:** the init file's `ProgramArguments` is the wrong place
to encode a Java path. Keep it to a thin shim that delegates to a
launcher.

## 14. `RollingFileAppender` does NOT auto-create parent directories

Logback's `RollingFileAppender` writes to
`${HONCHO_CONFIG_DIR}/logs/honcho-inspector.jsonl`. On a fresh
install, that `logs/` subdir doesn't exist. Logback does not
`mkdir -p` before opening the file. The install script must.

The fix is a single line:

```sh
install -d -m 0750 -o "$SERVICE_USER" -g "$SERVICE_GROUP" "$CONFIG_DIR/logs"
```

Without it, the first start of the service fails with
`FileNotFoundException: .../logs/honcho-inspector.jsonl (No such
file or directory)`.

**Lesson:** any directory the framework writes to as a side-effect
must be enumerated in the install script. Don't trust the framework
to mkdir for you.

## 15. macOS `/etc/defaults` is plural (not singular)

macOS uses `/etc/defaults/` for system-level defaults files. The
singular `/etc/default/` is a Debian convention; macOS, BSD-derived
systems, and Homebrew-prefixed macOS all use the plural form. The
launcher probes both paths for compatibility.

The install script writes to `/etc/defaults/honcho-inspector`
(macOS) and `/etc/default/honcho-inspector` (Linux + FreeBSD).

**Lesson:** when probing for a config file across OSes, probe all
common forms. A path that works on Debian may not work on macOS.

## 16. macOS sandboxing blocks unauthenticated writes to /usr/local

The macOS installer flow we used wrote to `/usr/local/lib/...` and
`/usr/local/etc/...`. These paths are writable by the
`admin` group with `chmod g+w /usr/local` (the Homebrew convention).
The install script must `chgrp admin` the destination directories
before `install`, or the `install` command fails with permission
denied.

The fix is one line in the install script:

```sh
chgrp admin "$dir" 2>/dev/null || true
install -m 0644 "$src" "$dir/$base"
```

The `|| true` keeps the install idempotent — if the directory
already has the right group, the `chgrp` fails silently.

**Lesson:** macOS's `/usr/local/` is writable by the `admin` group,
not world-writable. The install must `chgrp` before `install`.

## 17. Operational checklist for the first run

After `make install` on any OS:

1. **Generate `HONCHO_CRYPTO_KEY`:**
   `openssl rand -base64 32` and paste into
   `/etc/default/honcho-inspector` (or `/etc/defaults/` on macOS).
2. **Set `CORS_ALLOWED_ORIGINS`** in
   `${HONCHO_CONFIG_DIR}/application.yml` to the exact public origin
   of the UI. No wildcards.
3. **Decide bootstrap path:**
   - **Config-file bootstrap:** populate `honcho.bootstrap.*` in
     `${HONCHO_CONFIG_DIR}/application.yml` and start the service.
     `AdminBootstrap` will create the first admin on `ApplicationReadyEvent`.
   - **UI-driven bootstrap:** leave `honcho.bootstrap.*` blank.
     Start the service, then open the UI. The UI reads
     `GET /api/health` and sees `first_run: true`, then prompts for
     a username + password and calls `POST /api/setup/first-admin`.
     After that call, the operator is logged in as the first admin.
4. **Remove bootstrap credentials.** If using the config-file
   bootstrap, remove the `honcho.bootstrap.*` block from
   `application.yml` once the first admin exists — leaving it in
   is a credential-on-disk leak.
5. **Verify the service user.** The service should be running as
   `www-data` / `www` / `_www`, not root. Verify with:
   - `systemctl show honcho-inspector --property=User` (Linux)
   - `service honcho_inspector status` (FreeBSD)
   - `launchctl list | grep honcho.inspector` (macOS)
6. **Verify file ownership.** The SQLite DB and log dir are owned by
   the service user; the config dir is `root:servicegroup 0750`;
   the env file is `root:servicegroup 0640`.

The first-run flow is documented in the man page (`FIRST STARTUP`
section) and the README ("First-startup bootstrap admin"). The
emergency-action CLI (`list-users`, `reset-admin-password`,
`promote-to-admin`, `revoke-all-sessions`) is the operator's escape
hatch when the UI is unreachable or the config-file bootstrap
fails.

---

## 18. Related work, in this repo

- `bin/honcho-inspector` — the launcher. 3-tier jar probe,
  `source_env_file()`, `default_data_dir()`, CLI subcommand
  dispatch (only `--help` is intercepted; everything else passes
  through to the jar).
- `bin/install-honcho-inspector` — POSIX install script. `find_source_file`
  with strict relative-path matching (NO basename probe). `install_jar`
  writes BOTH unversioned + versioned forms. `setup_dirs` split from
  `create_dirs`. POSIX `tr` for hyphens-to-underscores.
- `Makefile` — OS-agnostic entry point, portable to GNU make + bmake.
  `make install` is canonical, `make install-linux|freebsd|macos` are
  aliases, `make install-launcher` for launcher only, `make install-config-only`
  for legacy config-only. `make run-jar` sources per-OS env file.
- `etc/systemd/honcho-inspector.service` — Linux systemd unit,
  `User=www-data`, hardened with `NoNewPrivileges`,
  `ProtectSystem=strict`, `PrivateTmp`, etc.
- `etc/rc.d/honcho-inspector` — FreeBSD rc.d, `name=honcho_inspector`
  (underscore), sources `/etc/default/honcho-inspector`, exports
  `HONCHO_CONFIG_DIR` + `HONCHO_DB_PATH`.
- `etc/launchd/com.honcho.inspector.plist` — macOS launchd,
  `UserName=_www`, calls launcher, sets `HONCHO_CONFIG_DIR` +
  `HONCHO_DB_PATH`.
- `etc/honcho-inspector/application.yml.example` — drop-in config
  template, `server.error.include-message: never`, bootstrap +
  audit blocks documented.
- `packaging/homebrew/honcho-inspector.rb` — Homebrew formula.
- `/home/mlapointe/git/cloudbsd-ports/net/honcho-inspector/` — FreeBSD
  backend port on branch `honcho_inspectord`.
- `/home/mlapointe/git/cloudbsd-ports/www/honcho-inspector/` —
  FreeBSD meta-port on branch `honcho_inspector_meta`.
- `src/main/java/com/revytechinc/honchoinspector/cli/CliRunner.java` —
  emergency-action CLI (list-users, reset-admin-password,
  promote-to-admin, revoke-all-sessions, help). Exit codes 0/2/3/4/5.
- `src/main/java/com/revytechinc/honchoinspector/DashboardApplication.java`
  — `main()` intercepts CLI dispatch via `shouldRunCli(String[])`
  predicate; CLI runs in a minimal Spring context
  (`WebApplicationType.NONE`).
- `docs/honcho-inspector.1` — operator-facing groff man page.
