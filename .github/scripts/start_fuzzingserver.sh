#!/usr/bin/env bash
# Start the Autobahn fuzzingserver container the same way build.gradle.kts's AutobahnDockerTask
# does, for CI jobs that run a prebuilt test binary directly (no Gradle). Requires .docker/config
# from a repo checkout. Reports land in ./.docker/reports (bind mount) and are read via docker exec.
set -euo pipefail

mkdir -p .docker/reports/clients

docker rm -f fuzzingserver >/dev/null 2>&1 || true

# Flags mirror build.gradle.kts AutobahnDockerTask (proven config): 8G memory for the compression
# cases, image default CMD runs `wstest -m fuzzingserver -s /config/fuzzingserver.json`.
docker run -d --rm --name fuzzingserver \
  --memory=8g --memory-swap=14g \
  -p 9001:9001 \
  -v "$PWD/.docker/config:/config" \
  -v "$PWD/.docker/reports:/reports" \
  crossbario/autobahn-testsuite

# Wait for the server to accept connections on 9001.
for _ in $(seq 1 30); do
  if (exec 3<>/dev/tcp/localhost/9001) 2>/dev/null; then
    exec 3>&- 3<&-
    echo "fuzzingserver is up"
    exit 0
  fi
  sleep 1
done

echo "fuzzingserver did not become ready" >&2
docker logs fuzzingserver >&2 || true
exit 1
