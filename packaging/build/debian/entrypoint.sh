#!/bin/sh
# entrypoint.sh -- honcho-inspector-backend .deb builder (Debian 13 / trixie)
#
# The /src bind mount contains the honcho-inspector-backend source
# tree (mounted read-only). This script:
#   1. verifies /src and /out are mounted
#   2. runs `mvn -B -ntp -DskipTests package` to produce the fat jar
#   3. runs `fpm -s dir -t deb` to produce the .deb (mirrors the
#      fpm invocation in the host Makefile's `make deb` target)
#   4. writes the .deb to /out
#   5. chowns /out to the host's HOST_UID:HOST_GID
#   6. prints the path of the produced artifact
#
# POSIX sh only -- no bashisms. The /bin/sh on Debian trixie is dash,
# which is what we target here.
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
ARTIFACT="${NAME}_${VERSION}_all.deb"

OUT="/out"
SRC="/src"

# === preflight ====================================================
# /src must be a mountpoint. We test for the bind-mount's
# filesystem device being different from /'s, which is the
# operator's signal that they actually passed -v $PWD:/src:ro.
# If not, the source wasn't bind-mounted and we can't build.
if [ ! -d "${SRC}/debian/DEBIAN" ]; then
    printf 'entrypoint: %s is not the honcho-inspector-backend source tree (no debian/DEBIAN)\n' "${SRC}" >&2
    printf '  did you forget: -v $PWD:/src:ro\n' >&2
    exit 1
fi

# /out must exist and be writable. fpm writes the .deb here; if it's
# not writable the build fails for an obvious reason -- surface that
# clearly so the operator notices the missing -v ...:/out:rw flag.
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

# === maven build ==================================================
# Build the fat jar. Skip tests: the test suite is exercised by
# `make test` in CI; the build container produces a deployable
# artifact, not a verifier. The output path is `target/<name>.jar`
# (relative to /src) and is required to exist before fpm runs.
printf 'entrypoint: running mvn package (this may take several minutes on first run)\n'
cd "${SRC}"
mvn -B -ntp -DskipTests package

JAR="target/${NAME}-${VERSION}.jar"
if [ ! -f "${JAR}" ]; then
    printf 'entrypoint: mvn did not produce %s\n' "${JAR}" >&2
    exit 1
fi

# === fpm -t deb ===================================================
# fpm invocation mirrors the host Makefile's `make deb` target
# (see Makefile:269-298). Key differences from the Makefile:
#   - output path: /out/${ARTIFACT} (not ./dist/...), because the
#     operator binds /out to the host's dist dir.
#   - the working dir is /src, so the source:dest mappings stay
#     relative.
printf 'entrypoint: running fpm -t deb\n'
fpm -s dir -t deb \
    -p "${OUT}/${ARTIFACT}" \
    -n "${NAME}" \
    -v "${VERSION}" \
    -a all \
    --maintainer "${MAINTAINER}" \
    --description "${DESCRIPTION}" \
    --depends "openjdk-25-jre-headless | java25-runtime-headless | default-jre-headless (>= 21)" \
    --depends adduser \
    --config-files /etc/default/honcho-inspector \
    --deb-no-default-config-files \
    --deb-systemd etc/systemd/honcho-inspector.service \
    --deb-systemd-path etc/systemd/system \
    --after-install debian/DEBIAN/postinst \
    --before-remove debian/DEBIAN/prerm \
    --after-remove debian/DEBIAN/postrm \
    "${JAR}"=/usr/local/lib/honcho-inspector/honcho-inspector-backend.jar \
    bin/honcho-inspector=/usr/local/bin/honcho-inspector \
    etc/default/honcho-inspector=/etc/default/honcho-inspector \
    etc/honcho-inspector/application.yml.example=/usr/local/share/honcho-inspector/etc/honcho-inspector/application.yml.example \
    docs/honcho-inspector.1=/usr/local/share/man/man1/honcho-inspector.1 \
    debian/DEBIAN/changelog=/usr/local/share/doc/honcho-inspector-backend/changelog.Debian

# === chown /out to the host operator =============================
# HOST_UID/HOST_GID are forwarded from `podman build --build-arg`
# and reflect the host user's id:gid. The .deb is now on the host;
# we want it owned by the operator, not root in the container.
chown -R "${HOST_UID}:${HOST_GID}" /out

# === report ======================================================
printf 'BUILT: /out/%s\n' "${ARTIFACT}"
exit 0
