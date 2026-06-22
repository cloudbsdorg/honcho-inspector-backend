# Makefile for honcho-inspector-backend
#
# === Portability ===
# This Makefile MUST parse and execute correctly under BOTH:
#   - GNU Make 4.x  (Linux, macOS with Homebrew, etc.)
#   - FreeBSD make (bmake, the BSD-make from pkgsrc / brew)
#
# Avoid GNU-only features:
#   := or ?= for variables      -> use recursive  =  only
#   ifeq / ifneq / ifdef        -> use shell  case  inside recipes
#   $(@D), $(@F), ${var:...}    -> extract logic to scripts/*.sh
#   $(shell ...) for output     -> use backticks (works in both) or
#                                  inline $$(...) in recipes
#   echo -e for colors          -> use printf with hardcoded \033[NNm
#   [[ ... ]]                   -> use [ ... ] (POSIX test)
#   which foo                   -> use command -v foo
#
# Validate before shipping:
#   make -n                     # GNU make dry run
#   bmake -n                    # FreeBSD bmake dry run
#
# If a GNU-only feature is required, the fallback is to extract the logic
# to scripts/*.sh (shell scripts are portable across both makes) and call
# it from the Makefile.
#
# === Self-documenting help ===
# Every public target is declared  target: ## short description
# The trailing  ## description  is the help metadata.
# The help target greps the Makefile itself, so the Makefile is the
# single source of truth for the menu.
# NOTE: we hardcode 'Makefile' rather than using $(MAKEFILE_LIST) because
# the latter is GNU-make-only (bmake leaves it empty). The lesson's
# preference for $(MAKEFILE_LIST) is for projects with `include`d
# fragments; this project has none.
#
# === Scope ===
# This portability rule applies to AUTHORED Makefiles in this project.
# Vendored dependencies and upstream OSS Makefiles are explicitly out
# of scope; touching them requires explicit user authorization and a
# targeted minimal patch (never a rewrite).

# === Variables ===
# Use  =  (recursive) for portability. Tool paths are detected inline
# in recipes via $$(command -v ...) — no $(shell ...) needed, no
# captured-trailing-newline hazards.
PROJECT_NAME = honcho-inspector-backend
LAUNCHER     = bin/honcho-inspector
DB_FILE      = honcho-inspector.db
JAR          = target/$(PROJECT_NAME)-0.1.0-SNAPSHOT.jar

# === Phony ===
.PHONY: help build package compile validate test verify \
        run start run-jar \
        db-shell db-vacuum db-reset \
        lint format outdated \
        install install-linux install-freebsd install-macos \
        install-launcher install-config-only \
        clean distclean

# === Default goal ===
.DEFAULT_GOAL := help

# === Help ===
help: ## Show this help menu
	@printf "\n\033[1m%s (%s/%s)\033[0m\n" \
		"$(PROJECT_NAME)" \
		"$$(uname -s)" \
		"$$(uname -m)"
	@printf "\n\033[1mTools\033[0m\n"
	@printf "\033[36m%-10s\033[0m %s\n" "maven"   "$$(command -v mvn     2>/dev/null | tr -d '\n')"
	@printf "\033[36m%-10s\033[0m %s\n" "java"    "$$(command -v java    2>/dev/null | tr -d '\n')"
	@printf "\033[36m%-10s\033[0m %s\n" "sqlite3" "$$(command -v sqlite3 2>/dev/null | tr -d '\n')"
	@printf "\n\033[1mTargets\033[0m\n"
	@printf "\033[36m%-22s\033[0m %s\n" "Target" "Description"
	@printf "\033[36m%-22s\033[0m %s\n" "------" "-----------"
	@grep -hE '^[a-zA-Z_-][a-zA-Z0-9_-]*:.*?## .*$$' Makefile | \
		awk -F':.*?## ' '{ printf "\033[36m%-22s\033[0m %s\n", $$1, $$2 }' | \
		sort
	@printf "\n"

# === Build ===
build: ## Build the executable jar (skip tests)
	@if [ -z "$$(command -v mvn)" ]; then printf "mvn not found in PATH\n" >&2; exit 1; fi
	mvn -B -ntp -DskipTests package

package: ## Build + run tests, produce fat jar
	@if [ -z "$$(command -v mvn)" ]; then printf "mvn not found in PATH\n" >&2; exit 1; fi
	mvn -B -ntp package

compile: ## Compile main sources only
	@if [ -z "$$(command -v mvn)" ]; then printf "mvn not found in PATH\n" >&2; exit 1; fi
	mvn -B -ntp compile

validate: ## Fast validation pass (no compile)
	@if [ -z "$$(command -v mvn)" ]; then printf "mvn not found in PATH\n" >&2; exit 1; fi
	mvn -B -ntp validate

# === Test ===
test: ## Run unit tests
	@if [ -z "$$(command -v mvn)" ]; then printf "mvn not found in PATH\n" >&2; exit 1; fi
	mvn -B -ntp test

verify: ## Full verify (tests + integration)
	@if [ -z "$$(command -v mvn)" ]; then printf "mvn not found in PATH\n" >&2; exit 1; fi
	mvn -B -ntp verify

# === Run ===
run: ## Run in dev mode (live reload, foreground)
	@if [ -z "$$(command -v mvn)" ]; then printf "mvn not found in PATH\n" >&2; exit 1; fi
	mvn -B -ntp spring-boot:run

start: build ## Build then start via the OS-aware launcher (foreground)
	@if [ ! -x "$(LAUNCHER)" ]; then \
		printf "launcher not found or not executable: %s\n" "$(LAUNCHER)" >&2; exit 1; \
	fi
	$(LAUNCHER)

run-jar: build ## Run the built fat jar directly (no launcher; sources per-OS env file)
	@if [ -z "$$(command -v java)" ]; then printf "java not found in PATH\n" >&2; exit 1; fi
	@if [ ! -f "$(JAR)" ]; then \
		printf "jar not found: %s (run 'make build' first)\n" "$(JAR)" >&2; exit 1; \
	fi
	@case "$$(uname -s)" in \
		Linux)             for f in /etc/default/honcho-inspector /etc/sysconfig/honcho-inspector; do [ -f "$$f" ] && { set -a; . "$$f" 2>/dev/null; set +a; }; done ;; \
		FreeBSD|DragonFly|OpenBSD|NetBSD) for f in /etc/default/honcho-inspector "$${HOMEBREW_PREFIX:-/usr/local}/etc/honcho-inspector/honcho-inspector.env"; do [ -f "$$f" ] && { set -a; . "$$f" 2>/dev/null; set +a; }; done ;; \
		Darwin)            for f in /etc/defaults/honcho-inspector "$${HOME:-}/Library/Application Support/honcho-inspector/env"; do [ -f "$$f" ] && { set -a; . "$$f" 2>/dev/null; set +a; }; done ;; \
	esac
	java -Xms64m -Xmx256m -jar "$(JAR)"

# === Database (dev default: ./honcho-inspector.db) ===
db-shell: ## Open sqlite3 on the dev-default db
	@if [ -z "$$(command -v sqlite3)" ]; then printf "sqlite3 not found in PATH\n" >&2; exit 1; fi
	@if [ ! -f "$(DB_FILE)" ]; then \
		printf "db not found: %s\n" "$(DB_FILE)" >&2; exit 1; \
	fi
	sqlite3 "$(DB_FILE)"

db-vacuum: ## VACUUM the dev-default db (reclaims space, requires no writers)
	@if [ -z "$$(command -v sqlite3)" ]; then printf "sqlite3 not found in PATH\n" >&2; exit 1; fi
	@if [ ! -f "$(DB_FILE)" ]; then \
		printf "db not found: %s\n" "$(DB_FILE)" >&2; exit 1; \
	fi
	sqlite3 "$(DB_FILE)" "VACUUM;"

db-reset: ## Delete the dev-default db (will reinit on next start)
	@if [ -f "$(DB_FILE)" ]; then \
		printf "deleting %s ...\n" "$(DB_FILE)"; \
		rm -f "$(DB_FILE)" "$(DB_FILE)-journal" "$(DB_FILE)-wal" "$(DB_FILE)-shm"; \
	else \
		printf "no db at %s, nothing to do\n" "$(DB_FILE)"; \
	fi

# === Quality ===
lint: ## Run configured linters (no checkstyle/spotless configured yet)
	@printf "no linter configured -- add checkstyle or spotless to pom.xml\n"

format: ## Run configured formatter (no spotless configured yet)
	@printf "no formatter configured -- add spotless to pom.xml\n"

outdated: ## Show dependency updates available
	@if [ -z "$$(command -v mvn)" ]; then printf "mvn not found in PATH\n" >&2; exit 1; fi
	mvn -B -ntp versions:display-dependency-updates

# === Install (system-level; see README) ===
# `make install` is the canonical, OS-agnostic install target. It
# invokes bin/install-honcho-inspector which auto-detects the OS
# (Linux, FreeBSD, macOS) and dispatches to the right per-OS
# service file (systemd unit, rc.d script, or launchd plist). The
# install-linux, install-freebsd, and install-macos targets are
# explicit-OS aliases of `install` (the script does the same work
# regardless); they exist for documentation and CI-matrix
# invocation.
#
# `install-launcher` installs only the bin/honcho-inspector wrapper
# to /usr/local/bin/ — useful in dev when you want manual launcher
# access without installing the full service.
#
# `install-config-only` is a legacy target that drops just the
# application.yml at the OS-appropriate config dir without touching
# the service, the jar, or the launcher. Retained for operators
# who want to seed a config dir first and run the full install
# later.

INSTALL_SCRIPT = bin/install-honcho-inspector
LAUNCHER_BIN   = bin/honcho-inspector

.PHONY: install install-linux install-freebsd install-macos \
        install-launcher install-config-only

install: build ## Full install: jar + launcher + service + man + config (auto-detect OS, requires sudo)
	@if [ -z "$$(command -v sudo)" ]; then \
		printf "sudo not found in PATH\n" >&2; exit 1; \
	fi
	@if [ ! -f "$(JAR)" ]; then \
		printf "jar not found: %s (run 'make build' first)\n" "$(JAR)" >&2; exit 1; \
	fi
	sudo "$(INSTALL_SCRIPT)"

install-linux: install ## Alias of install; OS is auto-detected by the install script

install-freebsd: install ## Alias of install; OS is auto-detected by the install script

install-macos: install ## Alias of install; OS is auto-detected by the install script

install-launcher: ## Install only the bin/honcho-inspector wrapper to /usr/local/bin/ (requires sudo)
	@if [ ! -w /usr/local/bin ]; then \
		printf "cannot write to /usr/local/bin -- re-run with sudo\n" >&2; exit 1; \
	fi
	@install -m 0755 "$(LAUNCHER_BIN)" /usr/local/bin/honcho-inspector
	@printf "installed launcher to /usr/local/bin/honcho-inspector\n"

install-config-only: ## Drop the application.yml at the OS-appropriate config dir (no service, no jar)
	@case "$$(uname -s)" in \
		Linux) \
			install -d -m 0755 /etc/honcho-inspector; \
			install -m 0644 etc/honcho-inspector/application.yml.example /etc/honcho-inspector/application.yml; \
			printf "installed config to /etc/honcho-inspector\n" ;; \
		FreeBSD) \
			install -d -m 0755 /usr/local/etc/honcho-inspector; \
			install -m 0644 etc/honcho-inspector/application.yml.example /usr/local/etc/honcho-inspector/application.yml; \
			printf "installed config to /usr/local/etc/honcho-inspector\n" ;; \
		Darwin) \
			dest="$$HOME/Library/Application Support/honcho-inspector"; \
			mkdir -p "$$dest"; \
			install -m 0644 etc/honcho-inspector/application.yml.example "$$dest/application.yml"; \
			printf "installed config to %s\n" "$$dest" ;; \
		*) printf "unsupported OS: %s\n" "$$(uname -s)" >&2; exit 1 ;; \
	esac

# === Cleanup ===
clean: ## Remove build artifacts
	@if [ -z "$$(command -v mvn)" ]; then printf "mvn not found in PATH\n" >&2; exit 1; fi
	mvn -B -ntp clean
	@printf "removed target/\n"

distclean: clean db-reset ## Remove build artifacts + dev db
	@printf "removed build artifacts + dev db\n"
