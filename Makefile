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
# This dispatcher is consumed by BOTH:
#   - GNU Make 4.x  (Linux, macOS with Homebrew, etc.)
#   - FreeBSD make (bmake, the BSD-make from pkgsrc / brew)
#
# Detection uses `!=` (POSIX shell-assignment; works in both makes)
# and `tr A-Z a-z` (POSIX case conversion; works on all Unixes).
# The dispatch is `include Makefile.$(FRAGMENT_OS)` where
# FRAGMENT_OS is a single filename.
#
# The per-OS fragments are OS-agnostic in their make syntax (no
# `ifeq`, no `:=`, no `$(shell)`); they use only recursive
# variables and recipe-time shell. This is enforced in each
# fragment's header comment.
#
# Validate with:
#   make -n                     # GNU make dry run
#   bmake -n                    # FreeBSD bmake dry run
#
# === Help ===
# `make help` (defined in Makefile.common) aggregates every public
# target from Makefile.common and the per-OS fragment for the
# current OS, so the help output always shows what is actually
# available on the running machine.

# === Detect OS ===
# `!=` runs uname -s and assigns the result to UNAME_S. The shell
# then lowercases it via `tr A-Z a-z` (POSIX) and falls back to
# 'freebsd' (a shell case) if the result isn't in our support
# list. The whole thing is one shell invocation; the result is a
# single filename.
#
# Operators with an exotic OS can override on the command line:
#   make FRAGMENT_OS=linux build
#   make FRAGMENT_OS=freebsd install
#   make FRAGMENT_OS=darwin install
FRAGMENT_OS != case "$$(uname -s | tr A-Z a-z)" in \
                  linux) echo linux ;; \
                  darwin) echo darwin ;; \
                  *) echo freebsd ;; \
                esac

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
		*) printf "WARN: uname -s = %s is not in the documented support list; using Makefile.freebsd as best-effort\n" "$$(uname -s)" >&2 ;; \
	esac
