#!/usr/bin/env python3
"""Flush the Autobahn fuzzingserver report for one agent and fail on any FAILED case.

Standalone equivalent of `createAutobahnValidationAction` in build.gradle.kts, for CI jobs
that run a prebuilt Kotlin/Native test binary directly (no Gradle to run the finalizer).

  usage: validate_autobahn.py <AGENT>   e.g. validate_autobahn.py LinuxX64

Requires the `fuzzingserver` docker container to be running on localhost:9001.
"""
import base64
import json
import os
import socket
import subprocess
import sys
import time


def flush(agent, host="localhost", port=9001):
    """Poke /updateReports so the fuzzingserver materializes index.json (WS upgrade + close)."""
    try:
        s = socket.create_connection((host, port), timeout=10)
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            f"GET /updateReports?agent={agent} HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            "Upgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
        )
        s.sendall(req.encode())
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = s.recv(1)
            if not chunk:
                break
            resp += chunk
        # Masked Close frame, status 1000 (matches the Gradle validator).
        s.sendall(bytes([0x88, 0x82, 0x00, 0x00, 0x00, 0x00, 0x03, 0xE8]))
        time.sleep(0.3)
        s.close()
    except OSError as e:
        print(f"warning: could not flush reports for {agent}: {e}")


def read_index():
    out = subprocess.run(
        ["docker", "exec", "fuzzingserver", "cat", "/reports/clients/index.json"],
        capture_output=True,
        text=True,
    )
    if out.returncode != 0 or not out.stdout.strip():
        sys.exit(f"could not read /reports/clients/index.json from container: {out.stderr}")
    return json.loads(out.stdout)


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: validate_autobahn.py <AGENT>")
    agent = sys.argv[1]
    flush(agent)
    data = read_index()
    cases = data.get(agent) or {}
    if not cases:
        sys.exit(f"No Autobahn cases recorded for agent '{agent}' (test binary did not connect?)")
    # `behavior == FAILED` is the fuzzingserver's per-case verdict (matches the Gradle validator).
    failures = [
        f"{cid}: behaviorClose={r.get('behaviorClose')}"
        for cid, r in cases.items()
        if r.get("behavior") == "FAILED"
    ]
    print(f"{agent}: {len(cases)} cases, {len(failures)} failed")
    if failures:
        print("\n".join(f"  - {f}" for f in failures))
        sys.exit(f"{len(failures)} Autobahn failure(s) for {agent}")
    print(f"All Autobahn tests passed for {agent}!")


if __name__ == "__main__":
    main()
