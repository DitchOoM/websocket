#!/usr/bin/env bash
# Start the Autobahn fuzzingserver container the same way build.gradle.kts's AutobahnDockerTask
# does, for CI jobs that run a prebuilt test binary directly (no Gradle). Requires .docker/config
# from a repo checkout. Reports land in ./.docker/reports (bind mount) and are read via docker exec.
#
# Runtime-agnostic: uses $CONTAINER_RUNTIME if set, else the first of docker / container (Apple
# Containerization, macOS 26+) / podman on PATH. Image overridable via $AUTOBAHN_IMAGE (e.g. the
# multi-arch Alpine fork ghcr.io/<owner>/autobahn-testsuite:alpine, which runs natively on arm64).
set -euo pipefail

AUTOBAHN_IMAGE="${AUTOBAHN_IMAGE:-crossbario/autobahn-testsuite}"

# Memory cap for the fuzzingserver. Measured peak for a full 517-case single-agent run against
# the in-repo Alpine/CPython image is ~340 MiB (wstest buffers per-case wire logs until the
# report flush), so 2g is ~6x headroom. The historical PyPy-based crossbario image needs the
# proven 8g (JIT + GC headroom on the compression cases), so it keeps that default.
if [ -z "${AUTOBAHN_MEMORY:-}" ]; then
  case "$AUTOBAHN_IMAGE" in
    crossbario/*) AUTOBAHN_MEMORY=8g ;;
    *) AUTOBAHN_MEMORY=2g ;;
  esac
fi
# --memory-swap is the TOTAL (mem + swap): keep the proven 8g+6g for the PyPy image,
# 2g+1g headroom for the Alpine one.
if [ -z "${AUTOBAHN_MEMORY_SWAP:-}" ]; then
  case "$AUTOBAHN_IMAGE" in
    crossbario/*) AUTOBAHN_MEMORY_SWAP=14g ;;
    *) AUTOBAHN_MEMORY_SWAP=3g ;;
  esac
fi

if [ -n "${CONTAINER_RUNTIME:-}" ]; then
  RUNTIME="$CONTAINER_RUNTIME"
elif command -v docker >/dev/null 2>&1; then
  RUNTIME=docker
elif command -v container >/dev/null 2>&1; then
  RUNTIME=container
elif command -v podman >/dev/null 2>&1; then
  RUNTIME=podman
else
  echo "no container runtime found (docker / container / podman)" >&2
  exit 1
fi
echo "using container runtime: $RUNTIME, image: $AUTOBAHN_IMAGE"

mkdir -p .docker/reports/clients

"$RUNTIME" rm -f fuzzingserver >/dev/null 2>&1 || true

# Flags mirror build.gradle.kts AutobahnDockerTask (proven config): 8G memory for the compression
# cases, image default CMD runs `wstest -m fuzzingserver -s /config/fuzzingserver.json`.
RUN_ARGS=(run -d --rm --name fuzzingserver
  -p 9001:9001
  -v "$PWD/.docker/config:/config"
  -v "$PWD/.docker/reports:/reports")

case "$RUNTIME" in
  container)
    # Apple container: VM-per-container; -m sizes the VM. No --memory-swap / --platform
    # pinning needed — images must be (and the Alpine fork is) native arm64.
    RUN_ARGS+=(-m "$AUTOBAHN_MEMORY")
    ;;
  *)
    RUN_ARGS+=(--memory="$AUTOBAHN_MEMORY" --memory-swap="$AUTOBAHN_MEMORY_SWAP")
    # The historical crossbario/autobahn-testsuite image is amd64-only; pin the platform so it
    # also runs (under QEMU, when binfmt is registered) on arm64 runners rather than failing the
    # manifest match. The Alpine fork is multi-arch, so no pin needed there.
    case "$AUTOBAHN_IMAGE" in
      crossbario/*) RUN_ARGS+=(--platform linux/amd64) ;;
    esac
    ;;
esac

"$RUNTIME" "${RUN_ARGS[@]}" "$AUTOBAHN_IMAGE"

# Wait until wstest actually SERVES on 9001. The runtime's port proxy binds the host port
# instantly — before the server inside is listening — so a plain TCP check races and the first
# client connect gets "Connection reset". Poll for a real HTTP response instead (mirrors
# AutobahnDockerTask.isServerReady).
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
"$RUNTIME" logs fuzzingserver >&2 || true
exit 1
