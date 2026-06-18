#!/bin/bash
# Source this file to get Java 25 + Maven on PATH.
# Usage:  source .sisyphus/env.sh
export JAVA_HOME=/home/mlapointe/.jdks/openjdk-25.0.2
export PATH="$JAVA_HOME/bin:/home/mlapointe/tools/apache-maven-3.9.9/bin:$PATH"
# Test-mode defaults (in-memory SQLite + random crypto key)
export HONCHO_DB_PATH=jdbc:sqlite::memory:
export HONCHO_CRYPTO_KEY="${HONCHO_CRYPTO_KEY:-$(openssl rand -base64 32)}"
