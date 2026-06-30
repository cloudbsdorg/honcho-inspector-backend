#!/bin/sh
# packaging/container/entrypoint.sh
#
# Runtime entrypoint for the honcho-inspector container images.
# Execs the JVM with flags read from environment variables; every
# variable has an in-script default matching the canonical FHS layout
# so the image "just works" with no flags.
#
# Run-time overrides (no rebuild required):
#   podman run --env JAVA_XMX=1g \
#              --env HONCHO_DB_PATH=jdbc:sqlite:/srv/data/hi.db \
#              honcho-inspector-backend
#
# Why an entrypoint script and not `CMD java -Xms${ARG_JAVA_XMS} ...`?
# Because the podman 5.x chroot backend (the default on this distro)
# does not substitute $ARG values into CMD/ENV/EXPOSE. Buildkit and
# Docker substitute them; this script is portable across all three.

set -eu

: "${JAVA_XMS:=64m}"
: "${JAVA_XMX:=256m}"
: "${SERVER_ADDRESS:=127.0.0.1}"
: "${HONCHO_CONFIG_DIR:=/etc/honcho-inspector}"
: "${HONCHO_LOG_FILE:=/var/log/honcho-inspector/honcho-inspector-backend.log}"
: "${HONCHO_JAR_PATH:=/usr/local/lib/honcho-inspector/honcho-inspector-backend.jar}"
# No default for HONCHO_UI_DIST -- the standalone backend image does
# not ship a UI dist, so leaving the variable unset means the JVM
# never sees `-Dhoncho.ui.dist=...`. The all-in-one image sets it
# explicitly via `ENV HONCHO_UI_DIST=...` in its Containerfile.
: "${HONCHO_DB_PATH:=jdbc:sqlite:/var/lib/honcho-inspector/honcho-inspector.db}"

# Spring relaxed binding reads env > -D, so the spring datasource picks
# this up without needing -Dhoncho.db-path.
export HONCHO_DB_PATH

# ${HONCHO_UI_DIST:+...} appends the flag only when the variable is set
# and non-empty. The all-in-one image sets it; the standalone backend
# doesn't, so the standalone image's JVM never sees -Dhoncho.ui.dist.
# JVM `-X` flags take their value attached, not space-separated
# (`-Xms64m`, not `-Xms 64m`); older Hotspot accepts both, but
# Eclipse Temurin 25's launcher is strict. Quoting the substitutions
# is unnecessary (`$JAVA_XMS` is one token in shell), so the flag
# stays a single argv entry.
# `-Xms${JAVA_XMS}` stays a single argv entry (no quotes around
# the substitution). Eclipse Temurin 25's launcher is strict about
# `-Xms` and rejects `-Xms 64m` (two argv entries) with
# "Invalid initial heap size". Same for the other -X flags.
exec java \
    -Xms${JAVA_XMS} \
    -Xmx${JAVA_XMX} \
    -Dserver.address=${SERVER_ADDRESS} \
    -Dhoncho.config.dir=${HONCHO_CONFIG_DIR} \
    -Dlogging.file.name=${HONCHO_LOG_FILE} \
    ${HONCHO_UI_DIST:+-Dhoncho.ui.dist=${HONCHO_UI_DIST}} \
    -jar ${HONCHO_JAR_PATH}
