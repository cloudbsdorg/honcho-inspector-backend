#!/bin/sh
# entrypoint.sh -- honcho-inspector-backend .rpm builder (Rocky Linux 10)
#
# The /src bind mount contains the honcho-inspector-backend source
# tree (mounted read-only). This script:
#   1. verifies /src and /out are mounted
#   2. generates a per-distro postinst that uses `useradd`/`groupadd`
#      (Rocky uses shadow-utils; the project's debian/DEBIAN/postinst
#      uses `adduser`/`addgroup` which only exist on Debian)
#   3. runs `mvn -B -ntp -DskipTests package` to produce the fat jar
#   4. runs `fpm -s dir -t rpm` to produce the .rpm
#   5. writes the .rpm to /out
#   6. chowns /out to the host's HOST_UID:HOST_GID
#   7. prints the path of the produced artifact
#
# POSIX sh only -- no bashisms. /bin/sh on Rocky 10-minimal is bash
# in POSIX mode (bash is the only shell shipped); we keep the
# script portable to dash too.
#
# Required env (set by the Containerfile's ARG/ENV):
#   HOST_UID, HOST_GID -- the operator's uid:gid on the host, used
#                          for the final `chown -R /out`.

set -eu

# === constants ====================================================
NAME="honcho-inspector-backend"
VERSION="0.1.0-SNAPSHOT"
RELEASE="1"
ARCH="noarch"
MAINTAINER="Mark LaPointe <mark@cloudbsd.org>"
DESCRIPTION="Honcho Inspector backend (Spring Boot + SQLite). Multi-user admin surface for Honcho, runs as www-data on 127.0.0.1:8080. Fronted by the Angular UI's Vite proxy."
ARTIFACT="${NAME}-${VERSION}-${RELEASE}.${ARCH}.rpm"

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
# fpm's --after-install takes a shell script and embeds it as the
# rpm %post section. The script must use shadow-utils syntax
# (useradd / groupadd) on Rocky -- the Debian adduser/addgroup
# commands don't exist here. We synthesise the postinst inline so
# the build container is self-contained; we don't ship a static
# rpm/ subdir.
#
# Mirrors debian/DEBIAN/postinst's systemd-detection arm:
#   if [ -d /run/systemd/system ]; then systemctl daemon-reload ...
# That pattern is what the task says to read.
POSTINST="${WORK}/postinst.sh"
cat >"${POSTINST}" <<'POSTINST_EOF'
#!/bin/sh
set -e

# rpm invokes this with $1=<number-of-packages-installed-on-system>
# (always 1 on first install, 2+ on upgrade). Phase detection:
#   $1 == 1  : first install
#   $1 >= 2  : upgrade
PHASE="${1:-1}"

# Service user/group. shadow-utils (useradd/groupadd) is the
# canonical implementation on Rocky / RHEL / Alma.
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

# State, log, config directories. useradd -M (above) did not
# create the home; install -d does it now with the right mode.
install -d -m 0750 -o www-data -g www-data /var/lib/honcho-inspector
install -d -m 0750 -o www-data -g www-data /var/log/honcho-inspector
install -d -m 0750 -o root    -g www-data /etc/honcho-inspector

# /etc/default placeholder env file. Seed only on first install
# (PHASE=1) and only if missing -- the operator's edits on upgrade
# are never clobbered.
if [ "${PHASE}" = "1" ] && [ ! -f /etc/default/honcho-inspector ]; then
    cat > /etc/default/honcho-inspector <<'EOF'
# Honcho Inspector backend environment file.
# Secrets (HONCHO_CRYPTO_KEY, optional bootstrap credentials) belong here.
# Generate HONCHO_CRYPTO_KEY with:  openssl rand -base64 32
#HONCHO_CRYPTO_KEY=
EOF
    chmod 0640 /etc/default/honcho-inspector
    chown root:www-data /etc/default/honcho-inspector
fi

# application.yml drop-in. Seed only if missing -- the install is
# idempotent and respects operator edits on upgrade.
if [ ! -f /etc/honcho-inspector/application.yml ]; then
    install -m 0644 -o root -g www-data \
        /usr/local/share/honcho-inspector/etc/honcho-inspector/application.yml.example \
        /etc/honcho-inspector/application.yml || true
fi

# Reload systemd so the new unit is picked up; enable+start on
# first install, just daemon-reload on upgrade. Belt-and-suspenders
# matches the debian/DEBIAN/postinst pattern.
if [ -d /run/systemd/system ]; then
    systemctl daemon-reload
    if [ "${PHASE}" = "1" ]; then
        systemctl enable honcho-inspector.service || true
        systemctl restart honcho-inspector.service || true
    fi
fi

exit 0
POSTINST_EOF
chmod 0755 "${POSTINST}"

# === generate per-distro prerm ====================================
# fpm's --before-remove is the rpm %preun section. Stop the
# service if it's running so the jar is not in use when the
# payload gets removed.
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
# fpm's --after-remove is the rpm %postun section. On full
# uninstall (PHASE=0) remove the data dirs and the service user.
POSTRM="${WORK}/postrm.sh"
cat >"${POSTRM}" <<'POSTRM_EOF'
#!/bin/sh
set -e

# rpm invokes this with $1=<count-of-packages-still-installed>.
#   0 : last instance of the package just got removed (purge)
#   1 : an upgrade replaced this instance
PHASE="${1:-1}"

if [ "${PHASE}" = "0" ]; then
    rm -rf /var/lib/honcho-inspector
    rm -rf /var/log/honcho-inspector
    rm -rf /etc/honcho-inspector
    rm -f  /etc/default/honcho-inspector
    if getent passwd www-data >/dev/null 2>&1; then
        userdel --quiet --system www-data 2>/dev/null || true
    fi
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

# === fpm -t rpm ===================================================
# fpm invocation mirrors the host Makefile's `make deb` target's
# fpm flags, but for rpm. Key differences from the deb command:
#   - -t rpm (not -t deb)
#   - --rpm-digest sha256 (modern rpm default)
#   - systemd unit goes to /usr/lib/systemd/system/ (rpm convention;
#     /etc/systemd/system/ is for admin overrides)
#   - --after-install / --before-remove / --after-remove point at
#     the per-distro postinst/prerm/postrm we generated above
printf 'entrypoint: running fpm -t rpm\n'
fpm -s dir -t rpm \
    -p "${OUT}/${ARTIFACT}" \
    -n "${NAME}" \
    -v "${VERSION}" \
    --iteration "${RELEASE}" \
    -a "${ARCH}" \
    --maintainer "${MAINTAINER}" \
    --description "${DESCRIPTION}" \
    --depends "java-25-openjdk-headless >= 25" \
    --depends "shadow-utils" \
    --config-files /etc/default/honcho-inspector \
    --directories /var/lib/honcho-inspector \
    --directories /var/log/honcho-inspector \
    --directories /etc/honcho-inspector \
    --rpm-digest sha256 \
    --rpm-compression gzip \
    --after-install "${POSTINST}" \
    --before-remove "${PRERM}" \
    --after-remove "${POSTRM}" \
    "${JAR}"=/usr/local/lib/honcho-inspector/honcho-inspector-backend.jar \
    bin/honcho-inspector=/usr/local/bin/honcho-inspector \
    etc/default/honcho-inspector=/etc/default/honcho-inspector \
    etc/systemd/honcho-inspector.service=/usr/lib/systemd/system/honcho-inspector.service \
    etc/honcho-inspector/application.yml.example=/usr/local/share/honcho-inspector/etc/honcho-inspector/application.yml.example \
    docs/honcho-inspector.1=/usr/local/share/man/man1/honcho-inspector.1 \
    debian/DEBIAN/changelog=/usr/local/share/doc/honcho-inspector-backend/changelog

# === chown /out to the host operator =============================
chown -R "${HOST_UID}:${HOST_GID}" /out

# === report ======================================================
printf 'BUILT: /out/%s\n' "${ARTIFACT}"
exit 0
