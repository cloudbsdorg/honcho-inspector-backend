#!/bin/bash
# Start the workspace backend in the background. Used by `make run-backend`
# or invoked directly. Idempotent: if a backend is already listening on
# port 8080, this script exits 0 without doing anything.

set -u

JAR="/home/mlapointe/secure/git/honcho-inspector-backend/target/honcho-inspector-backend-0.1.0-SNAPSHOT.jar"
LOG_DIR="/var/log/honcho-inspector"
LOG_FILE="${LOG_DIR}/workspace-backend.log"
OUT="/tmp/hi-services/backend.out"
PIDFILE="/tmp/hi-services/backend.pid"

# Don't start if already listening.
if ss -tlnp 2>/dev/null | grep -q ':8080 '; then
    echo "backend already listening on 8080"
    exit 0
fi

# Refuse to start if the jar is missing.
if [ ! -f "${JAR}" ]; then
    echo "jar not found: ${JAR} (run 'mvn package' first)" >&2
    exit 1
fi

# Make sure the log dir is writable for mlapointe (we don't pre-create
# the per-launch log file; the JVM will create it on first write).
if [ ! -w "${LOG_DIR}" ]; then
    echo "log dir not writable by $(id -un); chmod 0775 first" >&2
    exit 1
fi

# Source /etc/default/honcho-inspector for HONCHO_CRYPTO_KEY and friends.
if [ -f /etc/default/honcho-inspector ]; then
    set -a; . /etc/default/honcho-inspector; set +a
fi

# Force the config dir to /etc/honcho-inspector (the dev-default
# ${PWD}/honcho-inspector.db is a stale pre-JPA-migration DB whose
# schema lacks the email column that schema.sql now expects).
export HONCHO_CONFIG_DIR="${HONCHO_CONFIG_DIR:-/etc/honcho-inspector}"

mkdir -p /tmp/hi-services
: > "${OUT}"

# Start detached. Use setsid to detach from the parent process group so
# the parent shell can exit without taking us down. Pass the env via
# `env` so the JVM inherits HONCHO_CONFIG_DIR + HONCHO_CRYPTO_KEY
# (setsid + bash -c can drop env depending on the platform).
setsid env \
    HONCHO_CONFIG_DIR="${HONCHO_CONFIG_DIR}" \
    HONCHO_CRYPTO_KEY="${HONCHO_CRYPTO_KEY}" \
    java -Dserver.address=127.0.0.1 \
    -Dlogging.file.name=${LOG_FILE} \
    -jar ${JAR} >>${OUT} 2>&1 </dev/null >/dev/null 2>&1 &
echo $! > "${PIDFILE}"
disown
echo "backend starting (pid $(cat ${PIDFILE}))"
