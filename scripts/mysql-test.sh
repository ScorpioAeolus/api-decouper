#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
container="api-decouper-it-$$"
created=false
cleanup() {
  if [ "$created" = true ]; then docker stop "$container" >/dev/null; fi
}
trap cleanup EXIT
# This script owns only the container it creates; no existing instance is modified.
docker run --rm -d --name "$container" --label api-decouper.test=true \
  -p 127.0.0.1::3306 -e MYSQL_ROOT_PASSWORD=decouper-test-only \
  -e MYSQL_DATABASE=decouper_test mysql:8 --default-time-zone=+00:00 >/dev/null
created=true
ready=false
for attempt in $(seq 1 60); do
  if docker exec "$container" mysql --protocol=TCP -h127.0.0.1 -uroot -pdecouper-test-only -Ddecouper_test -e "SELECT 1" >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
if [ "$ready" != true ]; then
  echo "MySQL test fixture did not become ready" >&2
  exit 1
fi
binding=$(docker port "$container" 3306/tcp)
port=${binding##*:}
mvn -Pmysql-it verify -Dgpg.skip=true \
  "-Ddecouper.test.url=jdbc:mysql://127.0.0.1:${port}/decouper_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
