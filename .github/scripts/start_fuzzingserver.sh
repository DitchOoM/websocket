#!/usr/bin/env bash
# Start the Autobahn fuzzingserver container the same way build.gradle.kts's AutobahnDockerTask
# does, for CI jobs that run a prebuilt test binary directly (no Gradle). Requires .docker/config
# from a repo checkout. Reports land in ./.docker/reports (bind mount) and are read via docker exec.
set -euo pipefail

mkdir -p .docker/reports/clients

docker rm -f fuzzingserver >/dev/null 2>&1 || true

# Flags mirror build.gradle.kts AutobahnDockerTask (proven config): 8G memory for the compression
# cases, image default CMD runs `wstest -m fuzzingserver -s /config/fuzzingserver.json`.
# crossbario/autobahn-testsuite is amd64-only; pin the platform so it also runs (under QEMU, when
# binfmt is registered) on arm64 runners rather than failing the manifest match.
docker run -d --rm --name fuzzingserver \
  --platform linux/amd64 \
  --memory=8g --memory-swap=14g \
  -p 9001:9001 \
  -v "$PWD/.docker/config:/config" \
  -v "$PWD/.docker/reports:/reports" \
  crossbario/autobahn-testsuite

# Wait until wstest actually SERVES on 9001. Docker's port proxy binds the host port instantly —
# before the server inside is listening — so a plain TCP check races and the first client connect
# gets "Connection reset". Poll for a real HTTP response instead (mirrors AutobahnDockerTask.isServerReady).
for _ in $(seq 1 60); do
  code=$(curl -s -o /dev/null -m 2 -w '%{http_code}' "http://localhost:9001" 2>/dev/null || echo 000)
  case "$code" in
    2??|3??|4??)
      echo "fuzzingserver is ready (HTTP $code)"
      exit 0
      ;;
  esac
  sleep 1
done

echo "fuzzingserver did not become ready" >&2
docker logs fuzzingserver >&2 || true
exit 1
