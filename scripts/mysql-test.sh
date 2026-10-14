#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Use only an explicitly configured, already-running MySQL. No Docker/server lifecycle commands.
: "${DECOUPER_TEST_JDBC_URL:?Provide the JDBC URL of a dedicated test database}"
: "${DECOUPER_TEST_USERNAME:?Provide the dedicated database user}"
: "${DECOUPER_TEST_PASSWORD:?Provide the database password through the environment}"
if [ "${DECOUPER_TEST_ALLOW_RESET:-}" != true ]; then
  echo "Set DECOUPER_TEST_ALLOW_RESET=true to allow rebuilding tables in the dedicated test database" >&2
  exit 1
fi
mvn -Pmysql-it verify -Dgpg.skip=true "$@"
