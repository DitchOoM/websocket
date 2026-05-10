# Multi-arch (linux/amd64 + linux/arm64) Autobahn fuzzingserver image.
#
# crossbario/autobahn-testsuite:latest on Docker Hub is amd64-only. This file
# rebuilds the same recipe on top of pypy:2-bookworm, which is published as a
# multi-arch manifest with a real linux/arm64 variant. The result runs natively
# on Apple Silicon (Docker Desktop, Apple's `container` CLI, OrbStack, Colima)
# and on arm64 Linux hosts, with no qemu emulation.
#
# Build (multi-arch, push to a registry):
#   docker buildx build \
#     --platform linux/amd64,linux/arm64 \
#     -t <registry>/autobahn-testsuite:latest \
#     --push .
#
# Build (single arch, load locally):
#   docker buildx build --platform linux/arm64 -t autobahn-testsuite:arm64 --load .
FROM pypy:2-bookworm

# cryptography 3.3.2 is the last release supporting Python/PyPy 2.7. Its source
# is written against OpenSSL 1.1 (it references FIPS_mode, removed in OpenSSL
# 3.0). The upstream amd64 image dodges this with a manylinux wheel that bundles
# OpenSSL 1.1 — that wheel does NOT exist for linux/arm64 + pypy2.7, so on arm64
# pip falls back to compiling against the system libssl, which on bookworm is
# 3.x and the resulting .so fails to load at runtime. We sidestep the whole
# problem by building OpenSSL 1.1 to /opt/openssl11 and pointing pip at it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
         build-essential libffi-dev wget ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    case "$(uname -m)" in \
      aarch64) target=linux-aarch64 ;; \
      x86_64)  target=linux-x86_64 ;; \
      *) echo "unsupported arch: $(uname -m)" >&2; exit 1 ;; \
    esac; \
    cd /tmp && wget -qO openssl.tar.gz https://www.openssl.org/source/openssl-1.1.1w.tar.gz \
    && tar xzf openssl.tar.gz && cd openssl-1.1.1w \
    && ./Configure $target --prefix=/opt/openssl11 --openssldir=/opt/openssl11 no-shared no-tests \
    && make -j"$(nproc)" && make install_sw \
    && cd /tmp && rm -rf openssl-1.1.1w openssl.tar.gz

ENV OPENSSL_DIR=/opt/openssl11 \
    LDFLAGS="-L/opt/openssl11/lib" \
    CFLAGS="-I/opt/openssl11/include"

# Pin the same versions the upstream image uses, with two arm64-specific tweaks:
#   * pre-install pycparser (PyPy 2.7's bundled cffi can't be replaced by pip,
#     and on arm64 the pyOpenSSL build-isolation env can't resolve it on its own)
#   * use --no-build-isolation for cryptography/pyOpenSSL/Twisted so OPENSSL_DIR
#     and our pre-installed deps are visible to setup.py
RUN pip install --no-cache-dir 'pip==20.3.4' 'setuptools==44.0.0' 'wheel==0.36.2'
RUN pip install --no-cache-dir 'incremental==16.10.1'
RUN pip install --no-cache-dir 'pycparser==2.21'
RUN pip install --no-cache-dir --no-build-isolation 'cryptography==3.3.2'
RUN pip install --no-cache-dir --no-build-isolation 'pyOpenSSL==19.1.0'
RUN pip install --no-cache-dir --no-build-isolation 'Twisted==19.10.0'

COPY autobahntestsuite-latest-py2-none-any.whl /wheels/
RUN pip install --no-cache-dir --no-build-isolation \
        /wheels/autobahntestsuite-latest-py2-none-any.whl

EXPOSE 9001 8080

CMD ["wstest", "--mode", "fuzzingserver", "--spec", "/config/fuzzingserver.json"]
