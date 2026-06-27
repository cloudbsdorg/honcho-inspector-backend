# Lesson: Multi-OS Makefile dispatcher portability

**Topic:** splitting a single 312-line `Makefile` into a tiny dispatcher
plus per-OS fragments, and the cross-make / cross-grep portability
hazards that bit us when validating on real Linux, FreeBSD, and macOS.

**Date:** 2026-06-26

**Project:** honcho-inspector-backend (Java 25 / Spring Boot 3.5 / SQLite).

**Status:** end-to-end live-tested on three real OSes.

| OS                         | make(1) impl     | Live-tested on            | Status |
|----------------------------|------------------|---------------------------|--------|
| Linux x86_64 (Ubuntu 24.04) | GNU make 4.4.1   | `framework` (this box)    | PASS   |
| FreeBSD 16-CURRENT amd64   | bmake 20260508   | `pppoe2.cloudbsd.org`     | PASS   |
| macOS 27.0 (Tahoe) arm64   | BSD make 3.81    | `m5.cloudbsd.org`         | PASS   |

This lesson sits next to `installer-and-packaging.md` (which covers
the install script + service files). The split is: that file is about
shipping the *service*; this one is about shipping the *operator
interface to the build*.

## What we set out to do

The single-file `Makefile` had grown to 312 lines with per-OS logic
sprinkled across ~10 recipes in `case "$$(uname -s)" in ... esac`
blocks. Reading the per-OS cases side-by-side required scrolling. A
typo in one OS case (e.g. `linux` vs `Linux`) was easy to miss. We
split:

```
Makefile           - 88 lines  (dispatcher)
Makefile.common    - 170 lines (build / test / run / db / clean)
Makefile.linux     - 212 lines (systemd + deb + tools)
Makefile.freebsd   - 124 lines (rc.d + tools)
Makefile.darwin    - 129 lines (launchd + tools)
```

The dispatcher does `uname -s` and includes `Makefile.$(FRAGMENT_OS)`
exactly once. No duplicate-recipe warnings, clean target namespace.

## What bit us, and the fix

### 1. The `make tools` target itself

The original ask: "maybe add it [cleanrun] into the makefile as a
tool". The cleanrun wrapper is a 4-line POSIX shell script that execs
a command with a sanitized environment (strips `CI`, `DEBIAN_FRONTEND`,
`EDITOR`, `GIT_EDITOR`, `GIT_SEQUENCE_EDITOR`, `GCM_INTERACTIVE`,
`PAGER`, `GIT_PAGER`, etc.). It exists because polluted shell
environments (every developer has them; `export CI=true` once and
forget) make `mvn` pause for an editor, `apt-get` refuse to run, and
`git push` block on a credential prompt.

We put cleanrun in `bin/tools/cleanrun` (POSIX sh, no bashisms,
strips the polluting vars, execs with minimal `PATH=/usr/local/...:/bin`).
Added three Makefile targets in each per-OS fragment:

- `make tools` (canonical) — installs all bin/tools/ to the
  OS-appropriate bin dir.
- `make install-tools` (internal) — the actual install loop.
- `make uninstall-tools` — removes the same set.

Each per-OS target uses `sudo install -m 0755 -o root -g <group>` with
the per-OS group:

- Linux: `root:root`
- FreeBSD: `root:wheel`
- macOS: `root:wheel`

Wired into `bin/install-honcho-inspector` (`install_tools` function)
so `sudo bin/install-honcho-inspector` also installs cleanrun
alongside the launcher, and into the `deb` fpm mapping
(`bin/tools/cleanrun=/usr/local/bin/cleanrun`).

### 2. The cross-make parse-time shell-assignment problem

The dispatcher's first line of business is to compute `FRAGMENT_OS`
at parse time. The three make implementations disagree on the
syntax:

| Make         | `VAR != cmd` | `VAR := $(shell cmd)` |
|--------------|--------------|----------------------|
| GNU make 4.x | OK           | OK                   |
| bmake        | OK           | **fails** (warnings, empty value) |
| BSD make 3.81 (macOS) | **fails** (treated as literal `! =`) | OK |

We initially tried `!=` (works on GNU + bmake) and fell back to
`$(shell)` via an `ifeq`-guarded block when `findstring` detected
the literal "uname" in the variable. But `ifeq` is GNU-only; bmake
rejects it. So we couldn't use a conditional fallback at parse time.

The fix: require GNU make as the common denominator, use `:=` with
`$(shell)`. This works on macOS BSD make (verified live on
`m5.cloudbsd.org` with stock XCode CLT, no Homebrew, no gmake). For
bmake / FreeBSD and for BSD make / macOS operators who don't have
GNU make yet, document the install:

- Linux:    `make` is GNU make
- macOS:    `brew install make` (installs GNU make as `gmake`)
- FreeBSD:  `pkg install gmake` (installs `/usr/local/bin/gmake`)

GNU make is the same choice the kernel, glibc, systemd, and most
other large OSS projects make. Chasing cross-make parse-time
syntax is not worth the complexity.

### 3. The `case ... esac` inside `$(shell ...)` problem

First attempt at the OS-detection shell command used `case`:

```make
FRAGMENT_OS != case "$$(uname -s | tr A-Z a-z)" in \
                  linux) echo linux ;; \
                  darwin) echo darwin ;; \
                  *) echo freebsd ;; \
                esac
```

This fails when collapsed onto one line inside `$(shell ...)`. Make
passes the backslash continuations as literal text to `/bin/sh`, and
`/bin/sh` then sees `case "x$uname" in linux) echo linux ;;` and
gets confused by the `;;` pattern terminators (`Syntax error: end
of file unexpected`).

Fix: use `if/elif/fi` instead, on a single line:

```make
FRAGMENT_OS := $(shell UNAME=$$(uname -s | tr A-Z a-z); if [ "x$$UNAME" = "xdarwin" ]; then echo darwin; elif [ "x$$UNAME" = "xlinux" ]; then echo linux; elif [ "x$$UNAME" = "xfreebsd" ]; then echo freebsd; else printf "unsupported OS: %s\n" "$$UNAME" >&2; exit 1; fi)
```

Works on GNU make and BSD make alike. The fail-loud `exit 1` for
unsupported OSes is better than the silent fall-through to
"freebsd" we had briefly (which would have hidden portability
issues for OpenBSD / NetBSD / DragonFly).

### 4. The cross-grep regex problem

The `make help` target aggregates target names + descriptions from
`Makefile.common` and the active per-OS fragment. The original
recipe used:

```
grep -hE '^[a-zA-Z_][a-zA-Z0-9_-]*:.*?## .*$$'
```

GNU make + GNU grep on Linux parsed this fine. On FreeBSD (BSD grep)
and macOS (BSD grep), the recipe failed with
`grep: repetition-operator operand invalid` — BSD grep rejects
both `.*?` (non-greedy) and `$$` (literal end-of-line; `$` is the
end-of-line anchor in basic regex, `$$` is the literal `$` only
in GNU's basic regex).

Fix: use plain `.*` with an unambiguous separator, and split on
the first occurrence:

```
grep -hE '^[a-zA-Z_][a-zA-Z0-9_-]*:.* ## ' | \
awk -F':.* ## ' '{ printf "%-22s %s\n", $1, $2 }'
```

` ## ` is unambiguous because target names cannot contain `#`.
Works on GNU grep and BSD grep. Verified live on FreeBSD 16-CURRENT
and macOS 27.0.

### 5. The `$(HOMEBREW_PREFIX:%=...)` problem

For the darwin fragment, we wanted `TOOLS_BIN_DIR` to default to
`/usr/local/bin` (on stock macOS without Homebrew) or
`/opt/homebrew/bin` (on Apple Silicon with Homebrew). The GNU make
idiom for "substitute empty with default" is:

```make
TOOLS_BIN_DIR = $(HOMEBREW_PREFIX:%=%/bin)
```

But this is GNU-only. The portable alternative is recipe-time
shell:

```sh
tools_bin=$( [ -n "$HOMEBREW_PREFIX" ] && echo "$HOMEBREW_PREFIX/bin" || echo "/usr/local/bin" )
```

Doing it in a recipe preamble (one shell line at the top of the
recipe) keeps the Makefile parser-portable. Tested live on
`m5.cloudbsd.org` with stock macOS (no HOMEBREW_PREFIX set) —
resolved to `/usr/local/bin`, which is on the default PATH on every
macOS since 10.5 (Leopard).

### 6. The macOS sandbox surprise

`make tools` on `m5.cloudbsd.org` (stock macOS) failed with:

```
install: sandbox detected, falling back to direct copy
install: /bin/INS@B6OxfR: Operation not permitted
```

The macOS sandbox was blocking `sudo install ... /bin/cleanrun`
because the dispatch resolved `TOOLS_BIN_DIR` to `/bin` (HOMEBREW_PREFIX
unset, fall-through to `/`). Once we fixed the fall-through to
`/usr/local/bin`, the install worked.

## What worked

- `make -n <target>` (dry-run) on a non-target OS to preview a
  per-OS recipe is the cheapest portability check. We did this for
  the darwin and freebsd fragments from the Linux box.
- The `Makefile.common` + `Makefile.<os>` split is genuinely easier
  to read. Per-OS recipes are no longer buried inside 30-line
  `case` blocks.
- `findstring` works in all three makes. Useful for probing whether
  a parse-time assignment succeeded (we ended up not needing it
  after we settled on `:= $(shell)`, but the pattern is good to
  know).
- `make help` auto-aggregating from `Makefile.common` + the active
  per-OS fragment means the help always shows what's actually
  available. No stale help for OSes you're not on.

## What didn't work

- Trying to support all three makes at parse time. There is no
  portable "run a shell command and assign its output" syntax.
  Pick one make (GNU make) and require it; document the install
  for the other two.
- Trying to keep bmake happy with a `Makefile` that uses
  `$(shell ...)`. bmake 20260508 silently accepts the syntax
  but produces empty values. The first `make help` on FreeBSD
  worked *only because* we had switched to `!=` at that point.
  When we later switched to `:= $(shell)`, bmake broke — which is
  why we now require GNU make.
- The `Makefile.bak` we lost during the early split. When I was
  testing the dispatch pattern with `/tmp/test-*.mk` files in
  the repo directory, the test scratch files overwrote
  `Makefile.common` and `Makefile.linux`. Lesson: keep test
  scratch files in `/tmp`, never in the repo dir.

## Operator install matrix (final)

| OS         | Install GNU make                      | Then run                       |
|------------|---------------------------------------|--------------------------------|
| Linux      | (none — `make` is GNU make)           | `make tools` or `make install` |
| macOS      | `brew install make` (or `gmake`)      | `gmake tools` or `gmake install` |
| FreeBSD    | `pkg install gmake`                   | `gmake tools` or `gmake install` |

The `make tools` target installs `cleanrun` system-wide so
operators get a sanitized shell environment for all subsequent
make/build/apt/git invocations.

## Final shape

```
$ wc -l Makefile Makefile.common Makefile.linux Makefile.freebsd Makefile.darwin
  102 Makefile
  170 Makefile.common
  212 Makefile.linux
  124 Makefile.freebsd
  129 Makefile.darwin
  737 total
```

(Plus `bin/tools/cleanrun` at 4 lines shell + 30 lines of comments.)

737 lines, but each file is single-OS or no-OS, so a typo is local,
the per-OS cases are linearly readable, and the help / dispatch /
`make tools` all behave identically on the three real OSes we
support.
