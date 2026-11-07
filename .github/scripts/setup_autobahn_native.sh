#!/usr/bin/env bash
# Build a native (no container/VM) Autobahn|Testsuite install: CPython 2.7.18 + the exact pins
# from .docker/autobahn/requirements.txt + the testsuite at the same upstream commit the
# Dockerfile uses. Used by the macOS arm64 CI leg, where hosted runners can't nest a VM.
#
#   usage: setup_autobahn_native.sh <prefix>     (installs <prefix>/bin/wstest)
#
# CPython is built with pyenv's python-build. Homebrew dropped openssl@1.1, so python-build
# compiles its bundled OpenSSL 1.1.1 into <prefix>/openssl; cryptography 3.3.2 (last Py2
# release, needs OpenSSL 1.1) is then built against that same OpenSSL. The C extensions link
# it by absolute path, so the prefix is not relocatable: restore a cached copy to the same path.
set -euo pipefail

PREFIX="${1:?usage: setup_autobahn_native.sh <prefix>}"
PYENV_REF="${PYENV_REF:-v2.8.14}"
# Keep in sync with AUTOBAHN_TESTSUITE_REF in .docker/autobahn/Dockerfile.
AUTOBAHN_TESTSUITE_REF="${AUTOBAHN_TESTSUITE_REF:-b8a5120d905e30470e4475785c48e4cedc35f6cd}"
REQUIREMENTS="$(cd "$(dirname "$0")/../.." && pwd)/.docker/autobahn/requirements.txt"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

git clone -q --depth 1 --branch "$PYENV_REF" https://github.com/pyenv/pyenv.git "$WORK/pyenv"
# SKIP_HOMEBREW: don't pick up brew's readline/openssl@3 etc.; build the bundled OpenSSL 1.1.1.
PYTHON_BUILD_SKIP_HOMEBREW=1 TMPDIR="$WORK" \
  "$WORK/pyenv/plugins/python-build/bin/python-build" 2.7.18 "$PREFIX"

# pyenv's 2.7.18 definition bakes -std=c99 into the recorded build CFLAGS. distutils reuses them
# for every extension, and clang rejects -std=c99 for C++ sources (ujson, via autobahn[accelerate]).
sed -i '' -e 's/ -std=c99//g' -e 's/-std=c99 //g' \
  "$PREFIX/lib/python2.7/_sysconfigdata.py" "$PREFIX/lib/python2.7/config/Makefile"
rm -f "$PREFIX/lib/python2.7/_sysconfigdata.pyc"

PY="$PREFIX/bin/python"
export PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_PYTHON_VERSION_WARNING=1 TMPDIR="$WORK"
# Same bootstrap as the Dockerfile: last Py2-capable pip/setuptools/wheel, no build isolation
# (Twisted's setup_requires needs incremental preinstalled).
"$PY" -m pip install -q 'pip==20.3.4' 'setuptools==44.0.0' 'wheel==0.36.2'
"$PY" -m pip install -q 'incremental==16.10.1'
CFLAGS="-I$PREFIX/openssl/include" LDFLAGS="-L$PREFIX/openssl/lib" \
  "$PY" -m pip install -q --no-build-isolation -r "$REQUIREMENTS"
"$PY" -m pip install -q --no-build-isolation \
  "git+https://github.com/crossbario/autobahn-testsuite.git@${AUTOBAHN_TESTSUITE_REF}#subdirectory=autobahntestsuite"

"$PY" - <<'EOF'
import platform, ssl
from cryptography.hazmat.backends.openssl import backend
import wsaccel, autobahntestsuite
print("python %s %s, ssl: %s, cryptography: %s" % (
    platform.python_version(), platform.machine(), ssl.OPENSSL_VERSION,
    backend.openssl_version_text()))
EOF
test -x "$PREFIX/bin/wstest"
