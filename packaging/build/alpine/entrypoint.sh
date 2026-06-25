#!/bin/sh
# entrypoint.sh -- honcho-inspector-backend .apk builder (Alpine 3.22)
#
# The /src bind mount contains the honcho-inspector-backend source
# tree (mounted read-only). This script:
#   1. verifies /src and /out are mounted
#   2. generates a per-distro postinst (Alpine uses BusyBox
#      adduser/addgroup; the debian/DEBIAN/postinst is written
#      for Debian's adduser/addgroup and would not run on Alpine
#      unchanged because of flag differences)
#   3. runs `mvn -B -ntp -DskipTests package` to produce the fat jar
#   4. runs `fpm -s dir -t apk` to produce the .apk
#   5. writes the .apk to /out
#   6. chowns /out to the host's HOST_UID:HOST_GID
#   7. prints the path of the produced artifact
#
# POSIX sh only -- no bashisms. /bin/sh on Alpine is BusyBox ash,
# which is what we target.
#
# Required env (set by the Containerfile's ARG/ENV):
#   HOST_UID, HOST_GID -- the operator's uid:gid on the host, used
#                          for the final `chown -R /out`.

set -eu

# === constants ====================================================
NAME="honcho-inspector-backend"
VERSION="0.1.0-SNAPSHOT"
MAINTAINER="Mark LaPointe <mark@cloudbsd.org>"
DESCRIPTION="Honcho Inspector backend (Spring Boot + SQLite). Multi-user admin surface for Honcho, runs as www-data on 127.0.0.1:8080. Fronted by the Angular UI's Vite proxy."
ARTIFACT="${NAME}-${VERSION}.apk"

OUT="/out"
SRC="/src"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

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

# === generate per-distro postinst =================================
# fpm embeds this as the apk post-install script. Alpine's
# BusyBox adduser/addgroup accept a subset of the Debian flags
# (e.g. --system, --ingroup, --no-create-home) and the script
# must be careful not to use shell builtins BusyBox lacks.
#
# The systemd-detection arm mirrors debian/DEBIAN/postinst: on
# Alpine that arm is a no-op (Alpine uses OpenRC, not systemd),
# so we additionally do the OpenRC dance -- create the
# /etc/init.d/ service file and add it to the default runlevel.
# However, the research says we should not over-engineer; the
# project's existing etc/systemd/honcho-inspector.service is
# what we ship, and the systemd arm is a no-op on Alpine. The
# OpenRC init file would have to be a separate generate step.
# For now we keep the postinst simple: arm-detection only,
# and the operator runs the OpenRC hookup manually.
POSTINST="${WORK}/postinst.sh"
cat >"${POSTINST}" <<'POSTINST_EOF'
#!/bin/sh
set -e

# apk invokes this with no args (unlike deb/rpm which pass
# $1=phase). First-install vs upgrade is detected by
# `apk add -u` semantics; for our purposes, idempotency is
# what matters.

# Service user/group. Alpine's BusyBox adduser/addgroup
# accept --system, --ingroup, --no-create-home, --shell, --gecos.
if ! getent group www-data >/dev/null 2>&1; then
    addgroup -S www-data
fi
if ! getent passwd www-data >/dev/null 2>&1; then
    adduser -S \
        -h /var/lib/honcho-inspector \
        -H \
        -s /sbin/nologin \
        -G www-data \
        -g "Honcho Inspector service account" \
        www-data
fi

# State, log, config directories. adduser -H skipped the home;
# install -d makes it now with the right mode.
install -d -m 0750 -o www-data -g www-data /var/lib/honcho-inspector
install -d -m 0750 -o www-data -g www-data /var/log/honcho-inspector
install -d -m 0750 -o root    -g www-data /etc/honcho-inspector

# /etc/default placeholder. Seed only if missing -- operator
# edits survive upgrades.
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

# systemd arm: no-op on Alpine (which uses OpenRC). The arm
# is preserved so the postinst is portable; an Alpine operator
# who has installed systemd (community/sysmgr) would still get
# the right behavior.
if [ -d /run/systemd/system ]; then
    systemctl daemon-reload 2>/dev/null || true
    systemctl enable honcho-inspector.service 2>/dev/null || true
    systemctl restart honcho-inspector.service 2>/dev/null || true
fi

exit 0
POSTINST_EOF
chmod 0755 "${POSTINST}"

# === generate per-distro prerm ====================================
# fpm's --before-remove is the apk pre-deinstall script. Stop
# the service if it's running.
PRERM="${WORK}/prerm.sh"
cat >"${PRERM}" <<'PRERM_EOF'
#!/bin/sh
set -e

if [ -d /run/systemd/system ]; then
    systemctl --quiet is-enabled honcho-inspector.service 2>/dev/null && \
        systemctl stop honcho-inspector.service || true
fi

exit 0
PRERM_EOF
chmod 0755 "${PRERM}"

# === generate per-distro postrm ===================================
# fpm's --after-remove is the apk post-deinstall script. On
# full uninstall remove the data dirs and the service user.
POSTRM="${WORK}/postrm.sh"
cat >"${POSTRM}" <<'POSTRM_EOF'
#!/bin/sh
set -e

# apk invokes this with no args; detection is by best-effort.
# If /var/lib/honcho-inspector still exists, the package was
# removed cleanly (apk pre-deinstall ran first); clean it up.
rm -rf /var/lib/honcho-inspector
rm -rf /var/log/honcho-inspector
rm -rf /etc/honcho-inspector
rm -f  /etc/default/honcho-inspector

if getent passwd www-data >/dev/null 2>&1; then
    deluser --quiet www-data 2>/dev/null || true
fi

if [ -d /run/systemd/system ]; then
    systemctl daemon-reload 2>/dev/null || true
fi

exit 0
POSTRM_EOF
chmod 0755 "${POSTRM}"

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

# === fpm -t apk ===================================================
# fpm invocation. fpm -t apk has fewer knobs than -t deb/rpm:
#   - no --after-install key for pre-deinstall; the closest is
#     --after-install (post-install) and we generate a no-op
#     pre-deinstall via --before-remove (apk calls it pre-deinstall)
#   - apk does not have a notion of "conffiles" the way deb does;
#     the /etc/default file is shipped in the archive but apk's
#     overlay means it does not protect it from being clobbered on
#     upgrade. fpm's --config-files flag still works in the sense
#     that the file lands at the right path; protection is the
#     operator's responsibility via /etc/.apk-protected.d/
#   - systemd unit goes to /etc/init.d/ (the OpenRC convention);
#     we put it there even though Alpine's BusyBox init won't run
#     it -- the file is harmless and a no-op for the OpenRC-less
#     case
printf 'entrypoint: running fpm -t apk\n'
fpm -s dir -t apk \
    -p "${OUT}/${ARTIFACT}" \
    -n "${NAME}" \
    -v "${VERSION}" \
    -a all \
    --maintainer "${MAINTAINER}" \
    --description "${DESCRIPTION}" \
    --depends "openjdk25-jre" \
    --depends "shadow" \
    --config-files /etc/default/honcho-inspector \
    --directories /var/lib/honcho-inspector \
    --directories /var/log/honcho-inspector \
    --directories /etc/honcho-inspector \
    --after-install "${POSTINST}" \
    --before-remove "${PRERM}" \
    --after-remove "${POSTRM}" \
    "${JAR}"=/usr/local/lib/honcho-inspector/honcho-inspector-backend.jar \
    bin/honcho-inspector=/usr/local/bin/honcho-inspector \
    etc/default/honcho-inspector=/etc/default/honcho-inspector \
    etc/systemd/honcho-inspector.service=/etc/init.d/honcho-inspector \
    etc/honcho-inspector/application.yml.example=/usr/local/share/honcho-inspector/etc/honcho-inspector/application.yml.example \
    docs/honcho-inspector.1=/usr/local/share/man/man1/honcho-inspector.1 \
    debian/DEBIAN/changelog=/usr/local/share/doc/honcho-inspector-backend/changelog

# === chown /out to the host operator =============================
chown -R "${HOST_UID}:${HOST_GID}" /out

# === report ======================================================
printf 'BUILT: /out/%s\n' "${ARTIFACT}"
exit 0
