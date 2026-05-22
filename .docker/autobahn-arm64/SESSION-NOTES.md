# Session Notes — Autobahn on arm64, and related testing-infrastructure questions

Date: 2026-05-22
Branch: `claude/autobahn-arm64-alpine-gFoLv`

This file records a working session that started as "can we run the Autobahn
WebSocket test suite natively on arm64?" and broadened into a survey of
protocol/network test tooling. Captured for future reference.

---

## 1. Can Autobahn run in an arm64 Alpine container, natively?

**Question:** Would it be difficult to get the Autobahn test suite working in an
arm64 Alpine Linux Docker image, arm64-native?

**Findings:**

- The official `crossbario/autobahn-testsuite:latest` on Docker Hub is a
  single-arch **amd64-only** manifest (confirmed via `docker buildx imagetools
  inspect` — `"Architecture": "amd64"`).
- Inspecting the image history shows it is built from **`pypy:2-bookworm`**
  (PyPy 2.7 on Debian) and just `pip install`s a vendored wheel
  `autobahntestsuite-latest-py2-none-any.whl` plus pinned deps
  (`Twisted==19.10.0`, `cryptography==3.3.2`, `pyOpenSSL==19.1.0`,
  `incremental==16.10.1`). Entrypoint: `wstest --mode fuzzingserver --spec
  /config/fuzzingserver.json`.
- The suite is **Python 2 only** (`py2-none-any` wheel). Alpine 3.20 dropped
  Python 2, and PyPy 2.7 upstream binaries are **glibc-only** — running them on
  Alpine's musl needs `gcompat` shims and is flaky.

**Conclusion:** *Native arm64 Alpine* is a research project, not a quick task.
But `pypy:2-bookworm` **is** a multi-arch image with a real `linux/arm64/v8`
variant, so rebuilding the upstream recipe on it gives a properly arm64-native
image. We abandoned Alpine and went with Debian (bookworm) — hence the working
directory is `.docker/autobahn-arm64/`, not `...-alpine`.

---

## 2. Validation — what we actually tried

We attempted a real build in the dev sandbox (x86_64 host, Docker + buildx +
qemu-aarch64 emulation).

**What worked:**

- `pypy:2-bookworm` confirmed multi-arch (`linux/amd64` + `linux/arm64/v8`).
- buildx + qemu emulation builds arm64 images in the sandbox.
- The vendored wheel was extracted from the official image (`docker create` +
  `docker cp`) — it is `py2-none-any` (pure Python), so cross-arch safe.
- The `pip install` pipeline ran fine under qemu through the pure-Python deps.

**Two real arm64-specific bugs found** (reproducible on real Apple Silicon, not
sandbox quirks):

1. `pyOpenSSL==19.1.0`'s isolated build env cannot resolve `pycparser`/`cffi`
   on arm64 PyPy 2.7. **Fix:** pre-install `pycparser==2.21`, use
   `--no-build-isolation`.
2. `cryptography==3.3.2` fails at runtime with `undefined symbol: FIPS_mode`.
   amd64 dodges this with a manylinux wheel bundling OpenSSL 1.1; **no such
   wheel exists for arm64 + PyPy 2.7**, so pip compiles against bookworm's
   OpenSSL 3.x, where `FIPS_mode` was removed. **Fix:** build OpenSSL 1.1.1w
   from source to `/opt/openssl11` and link cryptography against it via
   `OPENSSL_DIR`.

**What could not be validated in the sandbox:** the sandbox egress proxy blocks
`deb.debian.org` and `openssl.org` (HTTP 403 `host_not_allowed`), so the
`apt-get build-essential` + OpenSSL source-build steps could not run here. This
is a sandbox limitation only — both hosts are reachable from any normal machine.

**Deliverable (committed in `639b641`):**

- `.docker/autobahn-arm64/Dockerfile` — multi-arch recipe (pypy:2-bookworm +
  OpenSSL 1.1 source build + pycparser pre-install).
- `.docker/autobahn-arm64/autobahntestsuite-latest-py2-none-any.whl` — vendored
  from the upstream image (1.36 MB).

Build commands:

```bash
# multi-arch, push to a registry
docker buildx build --platform linux/amd64,linux/arm64 \
  -t <registry>/autobahn-testsuite:latest --push .docker/autobahn-arm64

# single arch, load locally
docker buildx build --platform linux/arm64 \
  -t autobahn-testsuite:arm64 --load .docker/autobahn-arm64
```

---

## 3. Should we just port the suite to Kotlin/JVM?

**Verdict: no.**

- **Self-validation problem.** Autobahn's value is being an *independent*
  reference. Writing the harness in Kotlin means either driving it with our own
  WebSocket lib (a bug in the lib could hide a bug in the test) or importing a
  separate JVM WebSocket lib purely to stay independent. Other libraries are
  also benchmarked against the canonical corpus, so "100% pass" only means
  something against the upstream tool.
- **Effort.** ~518 declarative test cases + report/index/HTML templates +
  compression handshake harness ≈ 4–8 engineer-weeks for a faithful port, plus
  ongoing maintenance. The arm64 Dockerfile is ~30 lines.

---

## 4. Apple Silicon performance / Apple `container`

- The amd64 image under Docker Desktop's Rosetta 2 emulation is **painfully
  slow** on Apple Silicon (user-confirmed; a ~6 min suite becomes ~20 min).
- Apple's `container` CLI (Linux VMs via Virtualization.framework) uses Rosetta
  2 for x86_64 too — **same bottleneck**, won't fix the slowness.
- Apple `container` shines with **native `linux/arm64`** images — real arm64
  code, no translation tax. Conclusion: the native arm64 image is the only real
  fix; Apple `container` needs no special handling beyond a proper arm64
  manifest entry.

---

## 5. Publishing — GHCR vs Docker Hub "fork"

- "Forking on Docker Hub" is not a concept — Docker Hub has namespaces, not
  forks. The existing community "forks" (`jettyproject/`, `theldus/`,
  `haegi/`, `franciscosbf/`, `jrudolph/`) are personal mirrors and are **all
  amd64-only** — none solve arm64.
- **Recommendation:** publish our multi-arch image to **GHCR**
  (`ghcr.io/ditchoom/autobahn-testsuite`) — free, public, no pull limits,
  integrated with GitHub Actions. Optionally submit the multi-arch Dockerfile
  upstream to `crossbario/autobahn-testsuite` (low urgency; the project is
  sleepy).

**Open item:** add `.github/workflows/autobahn-image.yaml` (buildx multi-arch
push to GHCR) and point `AutobahnDocker.kt:89` at the GHCR image instead of the
hardcoded `crossbario/autobahn-testsuite`.

---

## 6. Equivalent test suites for MQTT / QUIC

- **MQTT** — `eclipse-paho/paho.mqtt.testing`. Python 3 broker-side conformance
  suite for MQTT 3.1.1 / 5.0. Closest analog in spirit, but not distributed as
  a canonical Docker image; primarily tests brokers, not clients.
- **QUIC** — `quic-interop/quic-interop-runner`. More sophisticated than
  Autobahn: an N×N interop matrix where each implementation publishes a Docker
  image meeting a fixed interface, and the runner pairs them server×client
  across a battery of test cases. Every major impl participates. Results at
  `interop.seemann.io`.

Neither offers the exact "one canonical image you `docker run` and point your
client at" pattern Autobahn has — Paho expects source-level integration, QUIC
expects peer participation.

---

## 7. The QUIC interop-runner endpoint contract

`endpoint.sh` is the container `ENTRYPOINT` — it reads runner-injected env vars
and dispatches the impl in server or client mode. The runner spins up two of
your containers inside a simulated network and grades each test via pcap/qlog.

**Env vars injected by the runner:**

| Var | Scope | Meaning |
| --- | --- | --- |
| `ROLE` | always | `server` or `client` |
| `TESTCASE` | always | test case name (`handshake`, `transfer`, `zerortt`, …) |
| `REQUESTS` | client | space-separated URLs the client must fetch |
| `SERVER_PARAMS` / `CLIENT_PARAMS` | optional | extra impl-specific flags |
| `SSLKEYLOGFILE` | always | path to write NSS key log to |
| `QLOGDIR` | always | dir to write qlog files to |

**Mounted filesystem:** `/certs/{priv.key,cert.pem}`, `/www/` (server serves
from here), `/downloads/` (client writes fetched bytes; runner byte-compares),
`/logs/{server,client,sim}/`.

**Rules:** exit `127` for an unsupported test case (lets new tests roll out
without breaking participants); client exits `0` only after all `REQUESTS` are
fully written.

**Test cases:** `versionnegotiation`, `handshake`, `transfer`, `chacha20`,
`keyupdate`, `retry`, `resumption`, `zerortt`, `http3`, `multiconnect`, `v2`,
`rebind-port`, `rebind-addr`, `connectionmigration`.

**Network sim:** an ns-3-based `quic-network-simulator` sidecar applies the
per-test-case loss/delay/reorder/NAT profile between the two endpoints.

**What it would take to join:** the big cost is *having a QUIC stack at all*
(RFC 9000/9001/9002, + 9114 for HTTP/3 — months-to-years from scratch).
Existing JVM options to wrap instead: **Kwik** (pure-Java QUIC, has been on the
runner before) or Netty's incubator QUIC. Given a working stack, the runner
integration itself is ~1–2 weeks: `endpoint.sh`, multi-arch Dockerfile,
qlog/keylog wiring, per-testcase handling, and a PR to `implementations_quic.json`.

---

## 8. Network-condition and TLS-error test containers

**TLS errors → `badssl.com`** (`github.com/chromium/badssl.com`, self-hostable
as Docker). Exhaustive matrix: expired / wrong-host / self-signed /
untrusted-root / revoked / incomplete-chain certs; SSL 2/3, TLS 1.0–1.3;
rc4/3des/null/dh ciphers; 1000-sans, sha1, mixed-content, HSTS, HPKP. Each issue
is its own subdomain.

**Network conditions → no single canonical container.** Toolbox:

- `shopify/toxiproxy` — L4 TCP proxy; latency, bandwidth caps, slow_close,
  slicing, timeouts, partial reads.
- `alexei-led/pumba` — applies `tc`/`netem` to any running container's netns.
- `tc … netem` — the kernel primitive both wrap.
- `mahimahi` — trace-based cellular emulation (real LTE traces); closest to a
  real "2G/3G feel," but not a polished Docker image.
- Apple Network Link Conditioner — built-in macOS/iOS dev tool, Edge/3G/LTE
  profiles.

**Suggested CI shape** for transport-level testing: a small `docker-compose.yml`
chaining `client → toxiproxy → netem → badssl`, parameterized per test by TLS
endpoint and network profile. Considered a separate effort from Autobahn
(Autobahn = protocol/L7 edge cases; this = transport/L3–L6 edge cases).

---

## 9. `tc qdisc … netem` — what it does

`tc` = Linux traffic control (`iproute2`). `qdisc` = queueing discipline (the
packet scheduler on an interface). `netem` = the kernel's network-emulator
qdisc that deliberately degrades egress traffic.

```bash
tc qdisc add dev eth0 root netem \
    delay 300ms 50ms distribution normal \
    loss 2% \
    rate 50kbit
```

Other knobs: `corrupt`, `duplicate`, `reorder`, `limit`. Caveats: it shapes
**egress only** (use an `ifb` device or the peer's egress for ingress);
containers need `--cap-add NET_ADMIN`; revert with `tc qdisc del dev eth0 root`.

---

## 10. OSI layers (reference)

| Layer | Name | Examples | Tools targeting it |
| --- | --- | --- | --- |
| L1 | Physical | cable, WiFi radio, fiber | hardware; `ethtool` |
| L2 | Data Link | Ethernet, 802.11, VLAN | `ip link`, `bridge` |
| L3 | Network | IPv4/6, ICMP | `tc`/`netem`, `iptables` |
| L4 | Transport | TCP, UDP, QUIC* | `toxiproxy` |
| L5 | Session | (mostly defunct as a discrete layer) | — |
| L6 | Presentation | TLS/SSL, encoding | `badssl`, `testssl.sh` |
| L7 | Application | HTTP, WebSocket, MQTT | Autobahn, Paho |

Why it matters: bugs hide at different layers and only tools at that layer
catch them — a lib can pass Autobahn (L7) yet corrupt under reordering (L3),
hang on a mid-frame close (L4), or trust an expired cert (L6). *QUIC blurs
L4–L6, which is why the QUIC interop runner had to invent its own
all-encompassing test format.

---

## Open / next steps

- [ ] Build the arm64 image on real Apple Silicon to confirm the OpenSSL 1.1
      source-build step end-to-end (could not run in sandbox).
- [ ] Decide publishing: GHCR workflow (`autobahn-image.yaml`) + make the image
      public.
- [ ] Wire `AutobahnDocker.kt` to use the new image (auto-build on arm64 hosts,
      or pull from GHCR).
- [ ] Optional: submit the multi-arch Dockerfile upstream to
      `crossbario/autobahn-testsuite`.
