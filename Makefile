# Makefile - dispatcher for honcho-inspector-backend
#
# This file is intentionally tiny. It detects the current OS via
# `uname -s` and includes:
#   1. Makefile.common    - OS-agnostic build/test/run/db/clean
#   2. Makefile.<os>      - the per-OS fragment (linux, freebsd, darwin)
#
# === Layout ===
#
#   Makefile           - this file (dispatcher)
#   Makefile.common    - build / test / run / db / clean
#   Makefile.linux     - systemd + deb + tools
#   Makefile.freebsd   - rc.d + tools
#   Makefile.darwin    - launchd + tools
#
# === Portability ===
# This dispatcher requires GNU make (4.x or newer). The three
# platforms we support each ship GNU make under a different name:
#
#   - Linux:    /usr/bin/make            (GNU make on every distro)
#   - macOS:    /opt/homebrew/bin/make   (Homebrew, default on Apple Silicon)
#               /usr/local/bin/make      (Homebrew on Intel; CLT make is BSD)
#               The XCode CLT ships /usr/bin/make as BSD make 3.81,
#               which does NOT parse this Makefile. Operators on
#               stock macOS without Homebrew should run
#                 brew install make
#               which installs GNU make as `gmake` (not as `make`).
#               Either `gmake` or `make` (after `brew install make`)
#               works.
#   - FreeBSD:  gmake (install via `pkg install gmake`).
#               The default /usr/bin/make is bmake, which does not
#               parse this Makefile. Operators on stock FreeBSD
#               should install GNU make and use `gmake`.
#
# Why GNU make only:
#   The three popular make implementations disagree on the right
#   syntax for "run a shell command at parse time and assign its
#   output":
#
#     GNU make:   VAR != cmd          OR   VAR := $(shell cmd)
#     bmake:      VAR != cmd          ONLY
#     BSD make:   VAR := $(shell cmd) ONLY
#
#   `!=` and `$(shell)` are mutually exclusive across the three
#   implementations. The portable pattern would require conditionals
#   (ifeq/else/endif) AND shell-assignment operators to be
#   supported on ALL THREE makes simultaneously, which is not
#   possible (bmake rejects `ifeq`; BSD make rejects `!=`).
#
#   GNU make is the common denominator. Both Linux (every distro)
#   and macOS (via Homebrew) ship it as `make`. FreeBSD ships it
#   in `devel/gmake` (or the meta-port `gmake`). GNU make is the
#   most-portable choice and the one used by ~every large OSS
#   project (kernel, glibc, systemd, etc.).
#
# The per-OS fragments are parser-portable: no `ifeq`, no `:=`,
# no `$(shell)`, no GNU-only functions. Only recursive `=` and
# recipe-time shell. This is enforced in each fragment's header.
#
# Validate with:
#   make -n                     # GNU make dry run
#
# === Help ===
# `make help` (defined in Makefile.common) aggregates every public
# target from Makefile.common and the per-OS fragment for the
# current OS, so the help output always shows what is actually
# available on the running machine.

# === Detect OS ===
# `:=` with `$(shell ...)` is the GNU-make + BSD-make-compatible
# way to run a shell command at parse time. bmake does not support
# `$(shell)`, but we require GNU make anyway (see portability note
# above).
#
# We can't use `case ... esac` inside `$(shell ...)` on a single
# line because the `;;` pattern terminators confuse make's variable
# parser. Use `if/elif/fi` instead.
#
# The three supported OSes are linux, freebsd, and darwin (macOS).
# Anything else prints an error to stderr and the variable stays
# empty, which causes the `include Makefile.$(FRAGMENT_OS)` line
# below to fail loud with "No such file or directory" - which is
# the right outcome. We do NOT silently fall through to freebsd
# for unknown OSes; that's a hidden assumption that hides real
# portability issues.
#
# Operators can override on the command line:
#   make FRAGMENT_OS=linux build
#   make FRAGMENT_OS=freebsd install
#   make FRAGMENT_OS=darwin install
FRAGMENT_OS := $(shell UNAME=$$(uname -s | tr A-Z a-z); if [ "x$$UNAME" = "xdarwin" ]; then echo darwin; elif [ "x$$UNAME" = "xlinux" ]; then echo linux; elif [ "x$$UNAME" = "xfreebsd" ]; then echo freebsd; else printf "unsupported OS: %s (supported: linux, freebsd, darwin)\n" "$$UNAME" >&2; exit 1; fi)

# === Include ===
# Makefile.common first (defines build/test/run/db/clean and the
# help target). Then the per-OS fragment for the current OS.
# Only one per-OS fragment is included, so there are no
# duplicate-recipe warnings and the target namespace is clean.
#
# If you add a new OS, add a new Makefile.<os> file and update
# the case statement above (and the help aggregation grep in
# Makefile.common).
include Makefile.common
include Makefile.$(FRAGMENT_OS)

# === Variables ===
# Per-OS fragment sets MAKEFILE_OS to its own filename, which
# the help target in Makefile.common uses to filter the
# aggregation grep. Override for testing:
#   make MAKEFILE_OS=Makefile.darwin help
MAKEFILE_OS = Makefile.$(FRAGMENT_OS)

# === Phony (dispatcher) ===
.PHONY: help-os check-os

help-os: ## (debug) show which Makefile fragment is active
	@printf "uname -s:  %s\n" "$$(uname -s)"
	@printf "active:    %s\n" "$(MAKEFILE_OS)"

check-os: ## (debug) verify the per-OS fragment for this OS is in effect
	@case "$$(uname -s | tr A-Z a-z)" in \
		linux|freebsd|darwin) ;; \
		*) printf "ERROR: uname -s = %s is not in the documented support list (linux, freebsd, darwin only)\n" "$$(uname -s)" >&2; exit 1 ;; \
	esac
