---
id: intro
title: Overview
slug: /
sidebar_position: 1
---

# WebSocket

A composable, RFC 6455 WebSocket client for Kotlin Multiplatform — one API on JVM, Android, iOS,
macOS, tvOS, watchOS, Linux, Node.js, and the browser, each delegating to the best native transport.

You get a single `connect*` call that returns a typed `Connection<WebSocketMessage<B>>`: `send` and
`receive` messages as a Kotlin `Flow`, decode binary frames into *your own types* with a pluggable
codec, and — because it composes on top of [`socket`](https://github.com/DitchOoM/socket) — drop
WebSocket in as a rung of a transport fallback chain.

## Features

- **RFC 6455 compliant** — frame parsing, masking, fragmentation, close handshake. Passes all 517
  [Autobahn](https://github.com/crossbario/autobahn-testsuite) test cases.
- **One API, native transports** — NIO2 on JVM/Android, Network.framework on Apple, io_uring/epoll
  on Linux, native `WebSocket` in the browser.
- **Typed messages via codecs** — binary frames decode through a `Codec<B>` you supply, so you work
  with your own types, not raw bytes. See [Typed messages with codecs](recipes/typed-messages-with-codecs.md).
- **Composable as a transport** — `WebSocketTransport` *is* a `socket` `Transport`, usable as the
  always-reachable floor of a QUIC → WebTransport → TCP → WebSocket fallback. See
  [WebSocket as a transport](recipes/websocket-as-transport.md).
- **permessage-deflate** — RFC 7692 compression with context takeover and configurable window bits
  where the platform allows.
- **Zero-copy pipeline** — direct `ReadBuffer`/`WriteBuffer` I/O with SIMD-optimized masking; no
  intermediate `ByteArray` copies.
- **Sealed exceptions** — a `WebSocketException` hierarchy for precise error handling.
- **Network-aware reconnection** — compose with `socket`'s `ReconnectingConnection` for backoff that
  reacts to real network path changes. See [Reconnection](core-concepts/reconnection.md).

## Install

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.ditchoom:websocket-tcp:<latest-version>")
        }
    }
}
```

Pick the module that matches how you connect:

| Module | Entry point | Use when |
|--------|-------------|----------|
| `websocket-tcp` | `connectTcpWebSocket(options)`, `WebSocketTransport` | JVM/Android/Apple/Linux/Node.js over raw TCP+TLS; or composing a transport chain |
| `websocket-apple` | `connectAppleNativeWebSocket(options)` | Apple platforms wanting the smallest binary and system-managed TLS/proxy |
| `websocket` | `connectWebSocket(byteStream, options)`, `connectBrowserWebSocket(options)` | Bring-your-own `ByteStream`, or the browser |

Find the latest version on [Maven Central](https://central.sonatype.com/artifact/com.ditchoom/websocket).

## Supported Platforms

| Platform | Targets | Transport |
|----------|---------|-----------|
| JVM | `jvm` | NIO2 `AsynchronousSocketChannel` |
| Android | `android` | NIO2 (same as JVM) |
| iOS / macOS / tvOS / watchOS | `iosArm64`, `iosSimulatorArm64`, `iosX64`, `macosArm64`, `macosX64`, `tvos*`, `watchos*` | `NWConnection`, or `NSURLSessionWebSocketTask` (`websocket-apple`) |
| Linux | `linuxX64`, `linuxArm64` | io_uring or epoll |
| JavaScript | `js` (Node.js) | raw socket, RFC 6455 engine |
| JavaScript | `js` (Browser) | native `WebSocket` |

## Part of the DitchOoM stack

This library builds on:

- [`com.ditchoom:buffer`](https://github.com/DitchOoM/buffer) — zero-copy `ReadBuffer`/`WriteBuffer`
  and `buffer-codec` (the `Codec<B>` your binary frames run through).
- [`com.ditchoom:socket`](https://github.com/DitchOoM/socket) — TCP + TLS + transport fallback
  (`WebSocketTransport` plugs in here; `ReconnectingConnection` wraps a WebSocket connection).

Both are pulled in transitively — you only add the `websocket*` dependency.
