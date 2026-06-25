#!/bin/sh
# entrypoint.sh -- honcho-inspector-backend .xbps builder (Void Linux)
#
# The /src bind mount contains the honcho-inspector-backend source
# tree (mounted read-only). This script:
#   1. verifies /src and /out are mounted
#   2. runs `mvn -B -ntp -DskipTests package` to produce the fat jar
#   3. stages the install tree at FHS-relative paths in a temp
#      dir, including:
#         - the jar at /usr/local/lib/honcho-inspector/
#         - the launcher at /usr/local/bin/
#         - the env-file at /etc/default/
#         - the systemd unit at /usr/lib/systemd/system/
#         - the runit runscript tree at /etc/sv/honcho-inspector/
#         - the application.yml.example at /usr/local/share/.../
#         - the man page at /usr/local/share/man/man1/
#   4. writes an XBPS install-action script (post-install) that
#      detects the init system (runit vs systemd) and activates
#      the matching service (k3s pattern, mirroring the systemd
#      detection arm in debian/DEBIAN/postinst)
#   5. runs `xbps-create` to produce the .xbps
#   6. writes the .xbps to /out
#   7. chowns /out to the host's HOST_UID:HOST_GID
#   8. prints the path of the produced artifact
#
# POSIX sh only -- no bashisms. /bin/sh on Void glibc is bash
# in POSIX mode (bash is the only shell Void ships); we keep
# the script portable to dash.
#
# Required env (set by the Containerfile's ARG/ENV):
#   HOST_UID, HOST_GID -- the operator's uid:gid on the host, used
#                          for the final `chown -R /out`.

set -eu

# === constants ====================================================
NAME="honcho-inspector-backend"
VERSION="0.1.0-SNAPSHOT"
REVISION="1"
ARCH_XBPS="x86_64"
MAINTAINER="Mark LaPointe <mark@cloudbsd.org>"
DESCRIPTION="Honcho Inspector backend (Spring Boot + SQLite). Multi-user admin surface for Honcho."
HOMEPAGE="https://github.com/cloudbsdorg/honcho-inspector-backend"
LICENSE="BSD-3-Clause"
ARTIFACT="${NAME}-${VERSION}_${REVISION}.${ARCH_XBPS}.xbps"

OUT="/out"
SRC="/src"
BUILD="$(mktemp -d)"
trap 'rm -rf "${BUILD}"' EXIT

# === preflight ====================================================
if [ ! -d "${SRC}/debian/DEBIAN" ]; then
    printf 'entrypoint: %s is not the honcho-inspector-backend source tree (no debian/DEBIAN)\n' "${SRC}" >&2
    printf '  did you forget: -v $PWD:/src:ro\n' >&2
    exit 1
fi
if [ ! -d "${OUT}" ]; then
    printf 'entrypoint: %s is not a directory; pass -v $PWD/../dist:/out:rw\n' "${OUT}" >&2
    exit 1
fi
if ! : >"${OUT}/.write-test" 2>/dev/null; then
    printf 'entrypoint: %s is not writable; pass -v $PWD/../dist:/out:rw\n' "${OUT}" >&2
    rm -f "${OUT}/.write-test" 2>/dev/null || true
    exit 1
fi
rm -f "${OUT}/.write-test"

# === stage source to a writable working copy ====================
# /src is read-only (mounted :ro). Maven wants to write its local
# repo (~/.m2) AND its build output (./target) here. We copy the
# source to a writable working dir first, build there, and fpm uses
# the staged copy's artifacts. The host source tree is never modified.
printf 'entrypoint: staging source tree\n'
WORK="$(mktemp -d -t build.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "${WORK}/.m2"
cp -a "${SRC}/." "${WORK}/"
cd "${WORK}"

# === maven build ==================================================
# Build the fat jar. Skip tests: the test suite is exercised by
# `make test` in CI; the build container produces a deployable
# artifact, not a verifier. The output path is `target/<name>.jar`
# (relative to the working copy). First run downloads the dep
# tree (~80MB of Spring + Hibernate + JDBC + Tomcat jars).
printf 'entrypoint: running mvn package (this may take several minutes on first run)\n'
mvn -B -ntp -DskipTests \
    -Dmaven.repo.local="${WORK}/.m2" \
    package

JAR="target/${NAME}-${VERSION}.jar"
if [ ! -f "${JAR}" ]; then
    printf 'entrypoint: mvn did not produce %s\n' "${JAR}" >&2
    exit 1
fi

# === stage the install tree =======================================
# Layout mirrors what xbps-create's -s dir expects: every file
# under STAGE will land at its absolute path on the target system.
STAGE="${BUILD}/stage"
mkdir -p \
    "${STAGE}/usr/local/lib/honcho-inspector" \
    "${STAGE}/usr/local/bin" \
    "${STAGE}/usr/local/share/honcho-inspector/etc/honcho-inspector" \
    "${STAGE}/usr/local/share/man/man1" \
    "${STAGE}/usr/local/share/doc/honcho-inspector-backend" \
    "${STAGE}/usr/lib/systemd/system" \
    "${STAGE}/etc/default" \
    "${STAGE}/etc/sv/honcho-inspector" \
    "${STAGE}/var/lib/honcho-inspector" \
    "${STAGE}/var/log/honcho-inspector" \
    "${STAGE}/etc/honcho-inspector"

install -m 0644 "${JAR}" \
    "${STAGE}/usr/local/lib/honcho-inspector/honcho-inspector-backend.jar"
install -m 0755 "bin/honcho-inspector" \
    "${STAGE}/usr/local/bin/honcho-inspector"
install -m 0640 "etc/default/honcho-inspector" \
    "${STAGE}/etc/default/honcho-inspector"
install -m 0644 "etc/honcho-inspector/application.yml.example" \
    "${STAGE}/usr/local/share/honcho-inspector/etc/honcho-inspector/application.yml.example"
install -m 0644 "docs/honcho-inspector.1" \
    "${STAGE}/usr/local/share/man/man1/honcho-inspector.1"
install -m 0644 "debian/DEBIAN/changelog" \
    "${STAGE}/usr/local/share/doc/honcho-inspector-backend/changelog"
install -m 0644 "etc/systemd/honcho-inspector.service" \
    "${STAGE}/usr/lib/systemd/system/honcho-inspector.service"

# === runit runscript tree (k3s pattern) ===========================
# /etc/sv/<name>/run is the runit runscript. It must be executable
# and is run by runsv under the supervising runsvdir. The
# post-install script (below) symlinks this into the default
# runlevel (/etc/runit/runsvdir/default/) if that directory
# exists, so the service is auto-started at boot.
#
# We also include an /etc/sv/<name>/finish script (optional) and
# a log/run script (optional) for the runsv logging subsystem.
# The log/run pipes stdout/stderr to svlogd; for now we forward
# to the system logger via logger(1) so the logs show up in
# syslog AND in /var/log/honcho-inspector/honcho-inspector-backend.log
# (the latter is wired in via the JVM -Dlogging.file.name=).
cat >"${STAGE}/etc/sv/honcho-inspector/run" <<'RUN_EOF'
#!/bin/sh
# runit runscript for honcho-inspector (Void Linux).
#
# runsv invokes this with stdout connected to the runit logger
# (which forwards to svlogd) and stderr piped through to the
# logger. We exec the JVM with the same flags the systemd unit
# uses, so behavior is identical regardless of init system.
exec 2>&1
exec /usr/bin/java \
    -Xms64m -Xmx256m \
    -Dserver.address=127.0.0.1 \
    -Dhoncho.config.dir=/etc/honcho-inspector \
    -Dlogging.file.name=/var/log/honcho-inspector/honcho-inspector-backend.log \
    -jar /usr/local/lib/honcho-inspector/honcho-inspector-backend.jar
RUN_EOF
chmod 0755 "${STAGE}/etc/sv/honcho-inspector/run"

# /etc/sv/<name>/finish -- called by runsv when the service exits.
# For a long-running server we don't want runsv to respawn it on
# clean exit; we let systemd (or the operator) handle that. Return
# 0 to signal "do not bring the service back up automatically".
cat >"${STAGE}/etc/sv/honcho-inspector/finish" <<'FINISH_EOF'
#!/bin/sh
# runit finish script: exit 0 means "do not restart on clean exit".
# On a crash (non-zero exit) runsv will respawn us automatically
# after the configured delay (default 1s).
exit 0
FINISH_EOF
chmod 0755 "${STAGE}/etc/sv/honcho-inspector/finish"

# /etc/sv/<name>/log/run -- the runit logger script. svlogd writes
# to /var/log/honcho-inspector/sv/<name>/current (rotated by t/T).
# We ship this for completeness so the operator can tail the
# runit-side log if they want; the canonical JSONL log is at
# /var/log/honcho-inspector/honcho-inspector-backend.log via the
# JVM -Dlogging.file.name= above.
mkdir -p "${STAGE}/etc/sv/honcho-inspector/log"
cat >"${STAGE}/etc/sv/honcho-inspector/log/run" <<'LOG_EOF'
#!/bin/sh
# runit logger: feed stdout to svlogd. The /var/log/.../sv/<name>/
# tree is created at install time by the post-install script.
exec svlogd /var/log/honcho-inspector/sv/honcho-inspector
LOG_EOF
chmod 0755 "${STAGE}/etc/sv/honcho-inspector/log/run"

# === write the post-install (XBPS install-action) script =========
# XBPS install actions are written as shell functions in a file
# passed via xbps-create's --inst-script. The available hooks
# (the docs at https://docs.voidlinux.org/xbps/index.html list
# these) are:
#   post-install()   -- after the package files are unpacked
#   pre-remove()     -- before the package files are removed
#   post-remove()    -- after the package files are removed
#   pre-update()     -- before a package version is replaced
#   post-update()    -- after a package version is replaced
#   pre-configure()  -- (rarely used) before configuration files
#                        are applied
#
# The script must be POSIX sh. The function bodies are executed
# by xbps's /bin/sh.
#
# The init-system detection arms mirror the systemd-detection
# pattern in debian/DEBIAN/postinst (the if [ -d /run/systemd/system ]
# block). The Void post-install adds a second arm: a runit-detection
# block that registers the runscript under /etc/runit/runsvdir/default
# and runs `sv up honcho-inspector`. Both arms ship in the same
# package; the arm that fires at install time is whichever init is
# actually present (k3s pattern).
INST="${BUILD}/inst-script.sh"
cat >"${INST}" <<'INST_EOF'
# XBPS install-action script for honcho-inspector-backend.
# Functions executed by xbps at install / remove / update time.

# === helpers =====================================================
# activate_runit: register the runscript with the default runlevel
# and bring it up. Returns 0 if runit activation ran (even if sv
# was missing), 1 if runit is not present on this system.
activate_runit() {
    if [ ! -d /etc/runit/runsvdir/default ]; then
        return 1
    fi
    mkdir -p /var/log/honcho-inspector/sv/honcho-inspector 2>/dev/null || true
    chown -R www-data:www-data /var/log/honcho-inspector 2>/dev/null || true
    chmod 0755 /var/log/honcho-inspector/sv/honcho-inspector 2>/dev/null || true
    if [ ! -e /etc/runit/runsvdir/default/honcho-inspector ]; then
        ln -sf /etc/sv/honcho-inspector \
            /etc/runit/runsvdir/default/honcho-inspector || true
    fi
    # /usr/bin/sv is the runsv control binary. It is provided by
    # the `runit` package (a run-time dependency of this .xbps).
    if [ -x /usr/bin/sv ]; then
        /usr/bin/sv up honcho-inspector 2>/dev/null || true
    fi
    return 0
}

# activate_systemd: daemon-reload + enable + start the service.
# Returns 0 if systemd was activated, 1 if not present.
activate_systemd() {
    if [ ! -d /run/systemd/system ]; then
        return 1
    fi
    /usr/bin/systemctl daemon-reload 2>/dev/null || true
    /usr/bin/systemctl enable honcho-inspector.service 2>/dev/null || true
    /usr/bin/systemctl restart honcho-inspector.service 2>/dev/null || true
    return 0
}

# === post-install ================================================
# Called by xbps after the package files are unpacked. xbps
# does not pass $1 (unlike deb's "configure" or rpm's
# "instance count"); we detect first-install vs upgrade by
# probing whether the service user already exists.
post_install() {
    # Service user/group. Void's `shadow` package provides
    # useradd/groupadd with the same flags as other distros.
    if ! getent group www-data >/dev/null 2>&1; then
        groupadd -r www-data
    fi
    if ! getent passwd www-data >/dev/null 2>&1; then
        useradd -r \
            -d /var/lib/honcho-inspector \
            -M \
            -s /usr/sbin/nologin \
            -g www-data \
            -c "Honcho Inspector service account" \
            www-data
    fi

    # State, log, config directories. useradd -M did not create
    # the home; install -d does it now with the right mode.
    install -d -m 0750 -o www-data -g www-data /var/lib/honcho-inspector
    install -d -m 0750 -o www-data -g www-data /var/log/honcho-inspector
    install -d -m 0750 -o root    -g www-data /etc/honcho-inspector

    # /etc/default placeholder. Seed only on first install (the
    # service user did not exist before this script ran) and only
    # if missing -- operator's edits survive upgrades.
    if [ ! -f /etc/default/honcho-inspector ]; then
        cat > /etc/default/honcho-inspector <<'EOF'
# Honcho Inspector backend environment file.
# Secrets (HONCHO_CRYPTO_KEY, optional bootstrap credentials) belong here.
# Generate HONCHO_CRYPTO_KEY with:  openssl rand -base64 32
#HONCHO_CRYPTO_KEY=
EOF
        chmod 0640 /etc/default/honcho-inspector
        chown root:www-data /etc/default/honcho-inspector
    fi

    # application.yml drop-in. Idempotent -- seed only if missing.
    if [ ! -f /etc/honcho-inspector/application.yml ]; then
        install -m 0644 -o root -g www-data \
            /usr/local/share/honcho-inspector/etc/honcho-inspector/application.yml.example \
            /etc/honcho-inspector/application.yml || true
    fi

    # Activate the matching init system. Try runit first (Void's
    # default); if runit isn't present, fall through to systemd.
    # activate_runit returns 1 if /etc/runit/runsvdir/default
    # doesn't exist, in which case we call activate_systemd.
    activate_runit || activate_systemd || true
}

# === pre-remove ==================================================
# Called by xbps before the package files are removed. Stop the
# service so the jar isn't in use when the payload is unpacked.
pre_remove() {
    # Deactivate runit first.
    if [ -d /etc/runit/runsvdir/default ] && [ -x /usr/bin/sv ]; then
        /usr/bin/sv down honcho-inspector 2>/dev/null || true
        rm -f /etc/runit/runsvdir/default/honcho-inspector 2>/dev/null || true
    fi
    if [ -d /run/systemd/system ]; then
        /usr/bin/systemctl --quiet is-enabled honcho-inspector.service 2>/dev/null && \
            /usr/bin/systemctl stop honcho-inspector.service || true
    fi
}

# === post-remove =================================================
# Called by xbps after the package files are removed. If this is
# the last instance of the package (xbps sets $1 to the number of
# remaining instances, 0 == purge), clean up the data dirs and
# the service user.
post_remove() {
    if [ "${1:-1}" = "0" ]; then
        rm -rf /var/lib/honcho-inspector
        rm -rf /var/log/honcho-inspector
        rm -rf /etc/honcho-inspector
        rm -f  /etc/default/honcho-inspector
        if getent passwd www-data >/dev/null 2>&1; then
            userdel --quiet --system www-data 2>/dev/null || true
        fi
    fi
    if [ -d /run/systemd/system ]; then
        /usr/bin/systemctl daemon-reload 2>/dev/null || true
    fi
}
INST_EOF

# === write the conffiles list ====================================
# xbps-create's --config-files accepts a newline-separated list
# of paths that should be marked as configuration files (so
# xbps preserves them on upgrade). We use it for
# /etc/default/honcho-inspector (the operator-tunable env file).
CONF="${BUILD}/conffiles"
cat >"${CONF}" <<'CONF_EOF'
/etc/default/honcho-inspector
CONF_EOF

# === xbps-create =================================================
# Flags used:
#   -A x86_64    : target architecture
#   -n NAME      : package name
#   -v VERSION   : package version (without the _REVISION suffix;
#                   -r sets the revision)
#   -r REVISION  : build/revision number (separate from version)
#   -s STAGE     : staging dir; every file under it is recorded
#                   with its absolute path as the install path
#   -D DEPS      : run-time dependencies (xbps package names;
#                   openjdk25-jre is the JRE subpackage; shadow
#                   provides useradd/groupadd; runit provides sv;
#                   ca-certificates for HTTPS; bash for the
#                   install-action script's cleaner conditionals)
#   --inst-script INST   : the file with the post-install /
#                            pre-remove / post-remove functions
#   --config-files CONF  : the file with the conffiles list
#   -S, -M         : signed-by, maintained-by (omitted; we don't
#                    sign the .xbps and the maintainer is in the
#                    XBPS_REPOPS env or defaults to the build host)
#   -z std        : compression = zstd (xbps's default; smallest
#                    and fastest on the Void build host)
printf 'entrypoint: running xbps-create\n'
xbps-create \
    -A "${ARCH_XBPS}" \
    -n "${NAME}" \
    -v "${VERSION}" \
    -r "${REVISION}" \
    -s "${STAGE}" \
    -D "openjdk25-jre>=25" \
    -D "shadow" \
    -D "runit" \
    -D "ca-certificates" \
    -D "bash" \
    --desc "${DESCRIPTION}" \
    --homepage "${HOMEPAGE}" \
    --license "${LICENSE}" \
    --maintainer "${MAINTAINER}" \
    --inst-script "${INST}" \
    --config-files "${CONF}" \
    -z std \
    -o "${OUT}/${ARTIFACT}"

# === chown /out to the host operator =============================
chown -R "${HOST_UID}:${HOST_GID}" /out

# === report ======================================================
printf 'BUILT: /out/%s\n' "${ARTIFACT}"
exit 0
