# websocket — open follow-ups

Tracked here so the gaps from today's session (2026-05-14) aren't lost.

## Autobahn matrix — platform coverage

Today's run on this Linux+WSL host: **JVM 517/517 OK, LinuxX64 517/517 OK, NodeJS 517/517 OK, BrowserJS 354/354 OK, Android 517/517 OK** plus all five buffer-strategy stress variants 18/18 across the four non-browser agents. Gaps:

- [ ] **Apple Autobahn agents.** `macosX64Test` / `macosArm64Test` / `iosSimulatorArm64Test` need a Mac host. The Apple WebSocket path (`NWConnection` via `appleNativeImpl`) was not exercised today; the prior handoff matrix had it green on a separate macOS build, but post-today's-JS-zlib-changes nothing on Apple has been validated.
- [ ] **WASM Autobahn.** `wasmJsTest` was not run in `-PintegrationTests` mode today. wasmJs has its own `JsWasmStreamingCompression` actuals (just modified) — should be exercised end-to-end.
- [ ] **Real-device Android.** Only the `test_quic(AVD)` emulator was used. `adb reverse` works the same way on a USB-tethered device, so no code change needed — just running the matrix on a physical Pixel/etc. would close the loop.
- [ ] **Long-soak / endurance.** Autobahn cases run ≤ 480 s each; no cumulative-pressure run today (the earlier 9.5.1 / 9.6.1 OOM concerns were closed by [[case961_probe_landed]] + [[pool_acquire_leak_fix]] but a multi-hour soak under permessage-deflate context-takeover hasn't been replayed since the JS persistent-stream rework).

## Stress variants — flaky `BrowserJS-stress-Pooled`

- [ ] On the first matrix run today, `BrowserJS-stress-Pooled` showed 12/18 with 6 cases FAILED. On the rerun after republishing buffer with the trailer-drain fix it went 18/18. Either it's a real intermittent issue under a specific buffer state, or the prior 6 failures were cross-contamination from the trailer-drain bug. **Repeat the matrix 3–5 times** to characterize stability; if any reproduce, investigate.

## Happy Eyeballs depends on socket-side work

See `socket/TODO.md`. `PublicWssValidationTest.hivemqWssConnect` passes today on LinuxX64 thanks to the sequential addrinfo fallback. Tests against IPv6-only or racing-required topologies are still gaps.
