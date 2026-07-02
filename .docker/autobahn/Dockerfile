# =====================================================================
# Autobahn|Testsuite — Alpine multi-arch image (linux/amd64 + linux/arm64)
#
# Replaces the historical crossbario/autobahn-testsuite Docker Hub image,
# which is amd64-only (QEMU-emulated and slow on arm64 CI runners, and
# second-class under Apple's `container` runtime). The testsuite source
# is NOT vendored — it is pip-installed from the upstream repo at the
# pinned commit below; only this Dockerfile and requirements.txt (copied
# verbatim from that commit) live here.
#
# The testsuite is Python 2.7-only (never ported to Py3), so the base is
# python:2.7-alpine — the last CPython 2.7 Alpine build (Alpine 3.11,
# OpenSSL 1.1.1), published multi-arch (amd64, arm64, arm/v7, ...).
# DO NOT bump the base image:
#   → Alpine ≥3.16 dropped Python 2 entirely
#   → OpenSSL 3.x breaks cryptography<3.4 (last Py2-safe release)
#
# Local build:   docker build -t autobahn-testsuite:alpine .docker/autobahn
# Multi-arch:    docker buildx build --platform linux/amd64,linux/arm64 \
#                  -t ghcr.io/<owner>/autobahn-testsuite:alpine .docker/autobahn
# Apple container (macOS 26+): container build works too; the image is
# native arm64 so no Rosetta is involved.
# =====================================================================

# --------------------------------------------------------------- build
FROM python:2.7-alpine AS builder

# Upstream testsuite commit to install (crossbario/autobahn-testsuite).
# requirements.txt alongside this Dockerfile is copied verbatim from the
# same commit — keep both in sync when bumping.
ARG AUTOBAHN_TESTSUITE_REF=b8a5120d905e30470e4475785c48e4cedc35f6cd

# Toolchain for the C extensions in the pinned dependency set:
# cryptography 3.3.2 (openssl-dev, libffi-dev), Twisted 19.10.0,
# wsaccel via autobahn[accelerate], markupsafe.
RUN apk add --no-cache build-base libffi-dev openssl-dev git

# Last pip/setuptools/wheel generation that reliably supports Py2 and
# does not isolate builds (Twisted's setup_requires needs preinstalled
# incremental).
RUN pip install --no-cache-dir 'pip==20.3.4' 'setuptools==44.0.0' 'wheel==0.36.2' \
 && pip install --no-cache-dir 'incremental==16.10.1'

# Build every pinned dependency into wheels so the runtime stage needs
# no compiler.
COPY requirements.txt /tmp/requirements.txt
RUN pip wheel --no-build-isolation --wheel-dir /wheels -r /tmp/requirements.txt

# Build the testsuite wheel from upstream source at the pinned commit.
RUN git clone https://github.com/crossbario/autobahn-testsuite.git /src \
 && cd /src && git checkout "$AUTOBAHN_TESTSUITE_REF" \
 && cd /src/autobahntestsuite \
 && python setup.py bdist_wheel --dist-dir /wheels

# ------------------------------------------------------------- runtime
FROM python:2.7-alpine

LABEL org.opencontainers.image.title="AutobahnTestsuite (Alpine, multi-arch)" \
      org.opencontainers.image.description="RFC 6455 WebSocket protocol testsuite (wstest) on Alpine; amd64 + arm64" \
      org.opencontainers.image.source="https://github.com/crossbario/autobahn-testsuite" \
      org.opencontainers.image.licenses="Apache-2.0"

# Runtime shared libs only (no compiler): libffi for cffi/cryptography.
# OpenSSL 1.1 runtime libs ship with the base image.
RUN apk add --no-cache libffi

RUN pip install --no-cache-dir 'pip==20.3.4' 'setuptools==44.0.0' 'wheel==0.36.2'

COPY --from=builder /wheels /wheels
RUN pip install --no-cache-dir --no-index --find-links /wheels autobahntestsuite \
 && rm -rf /wheels

VOLUME /config
VOLUME /reports

WORKDIR /

EXPOSE 9001

CMD ["wstest", "--mode", "fuzzingserver", "--spec", "/config/fuzzingserver.json"]
