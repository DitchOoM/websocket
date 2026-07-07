WebSocket
==========

See the [project website][docs] for documentation and APIs.

**A composable, RFC 6455 WebSocket client for Kotlin Multiplatform — one API on JVM, Android, iOS, macOS, tvOS, watchOS, Linux, Node.js, and the browser, each delegating to the best native transport.**

WebSocket gives you a single `connect*` call that returns a typed `Connection<WebSocketMessage<B>>`: `send` and `receive` messages as a Kotlin `Flow`, decode binary frames into *your own types* with a pluggable codec, and — because it composes on top of [`com.ditchoom:socket`](https://github.com/DitchOoM/socket) — drop WebSocket in as the universal floor of a transport fallback chain.

[![Maven Central](https://img.shields.io/maven-central/v/com.ditchoom/websocket.svg)](https://central.sonatype.com/artifact/com.ditchoom/websocket)

## Why this WebSocket?

| Concern | Platform WebSocket / OkHttp / Ktor | This library |
|---------|-----------------------------------|--------------|
| Multiplatform | JVM-only (OkHttp), or a lowest-common-denominator API (Ktor) | One API; each platform delegates to its native transport (NIO2, Network.framework, io_uring, browser `WebSocket`) |
| Binary messages | Hand off a `ByteArray`/`ByteBuffer`, parse it yourself | Supply a `Codec<B>` — binary frames arrive as **your decoded type** (`WebSocketMessage.Binary<B>`) |
| Composition | An opaque client you can only talk WebSocket to | `WebSocketTransport` **is** a `socket` `Transport` — use it as a QUIC→WebTransport→TCP→**WebSocket** fallback rung |
| Allocations | Copies through intermediate `ByteArray`s | Zero-copy `ReadBuffer`/`WriteBuffer` pipeline with SIMD-optimized masking |
| Compression | Varies / unavailable | permessage-deflate (RFC 7692) everywhere, with context takeover + window bits where the platform allows |
| Conformance | Varies | Passes all 517 [Autobahn](https://github.com/crossbario/autobahn-testsuite) test cases |

## Installation

Pick the module for how you want to connect — all pull in [`buffer`](https://github.com/DitchOoM/buffer) and (for the socket-backed modules) [`socket`](https://github.com/DitchOoM/socket) transitively.

```kotlin
dependencies {
    // Raw-socket platforms (JVM, Android, iOS/macOS, Linux, Node.js): TCP + TLS + WebSocket in one call
    implementation("com.ditchoom:websocket-tcp:<latest-version>")

    // Apple-only, backed by NSURLSessionWebSocketTask (system TLS/proxy/deflate, no socket dependency)
    implementation("com.ditchoom:websocket-apple:<latest-version>")

    // Core engine only — bring your own pre-connected ByteStream (advanced / browser lives here too)
    implementation("com.ditchoom:websocket:<latest-version>")
}
```

Find the latest version on [Maven Central](https://central.sonatype.com/artifact/com.ditchoom/websocket).

## Modules

| Module | Entry point | Use when |
|--------|-------------|----------|
| `websocket` | `connectWebSocket(byteStream, options)` | You already have a connected `ByteStream`, or you're on the browser (`connectBrowserWebSocket`) |
| `websocket-tcp` | `connectTcpWebSocket(options)` and `WebSocketTransport` | JVM/Android/Apple/Linux/Node.js over raw TCP+TLS; or composing WebSocket into a `socket` transport chain |
| `websocket-apple` | `connectAppleNativeWebSocket(options)` | Apple platforms wanting the smallest binary and system-managed TLS/proxy |

## Quick Start

```kotlin
import com.ditchoom.websocket.WebSocketConnectionOptions
import com.ditchoom.websocket.WebSocketMessage
import com.ditchoom.websocket.use
import com.ditchoom.websocket.tcp.connectTcpWebSocket
import kotlinx.coroutines.flow.first

val options = WebSocketConnectionOptions(
    name = "echo.websocket.org",
    port = 443,
    tls = true,               // defaults to (port == 443)
    websocketEndpoint = "/",
)

// `use` runs the block and closes the connection (RFC 6455 close handshake) in a finally.
connectTcpWebSocket(options).use { ws ->
    ws.send(WebSocketMessage.Text("Hello, WebSocket!"))

    val reply = ws.receive().first()          // receive() is a Flow<WebSocketMessage<Unit>>
    println((reply as WebSocketMessage.Text).payload)
}
```

`connect*` suspends until the socket is connected, the HTTP upgrade completes, and permessage-deflate is negotiated — the returned `Connection` is live. `send`/`receive` are **not** thread-safe; confine each to its own coroutine.

### Messages

`WebSocketMessage<B>` is a sealed interface — exhaustive `when`, no casting on the receive path:

```kotlin
ws.receive().collect { message ->
    when (message) {
        is WebSocketMessage.Text   -> println("text: ${message.payload}")     // always a UTF-8 String
        is WebSocketMessage.Binary -> handle(message.payload)                  // payload: B (your codec's type)
        is WebSocketMessage.Ping   -> println("ping: ${message.appData}")      // auto-ponged for you
        is WebSocketMessage.Pong   -> println("pong: ${message.appData}")
        is WebSocketMessage.Close  -> println("close ${message.code}: ${message.reason}")
    }
}
```

## Typed binary messages with codecs

Text is always a `String` (RFC 6455 §5.6). **Binary frames run a `Codec<B>` you supply**, so `receive()` hands you your own decoded type instead of raw bytes — the generic threads through as `Connection<WebSocketMessage<B>>`.

```kotlin
// Given a Codec<SensorReading> (e.g. generated by buffer-codec):
val ws: Connection<WebSocketMessage<SensorReading>> =
    connectTcpWebSocket(options, binaryCodec = SensorReadingCodec)

ws.send(WebSocketMessage.Binary(SensorReading(id = 7u, celsius = 21)))

ws.receive().collect { msg ->
    if (msg is WebSocketMessage.Binary) update(msg.payload)   // payload: SensorReading, already decoded
}
```

Built-in codecs (`com.ditchoom.websocket.codecs`):

| Codec | `B` | Behavior |
|-------|-----|----------|
| `EmptyCodec` | `Unit` | Default — discards binary payloads |
| `BinaryPassThroughCodec` | `ReadBuffer` | Raw bytes copied into a consumer-owned buffer |
| `RejectingCodec` | `Nothing` | Throws on any binary frame — fail loudly when binary isn't expected |

Decode runs *after* fragment reassembly, and the wire buffer is freed before your value is emitted, so decoded values never alias library buffers. A custom codec can allocate from the same pool the wire path uses via the `WsBufferFactoryKey` injected into its `DecodeContext`.

## WebSocket as a transport rung

`connectWebSocket` *takes* a `ByteStream` and gives you WebSocket messages. `WebSocketTransport` is the inverse: it **is** a [`socket`](https://github.com/DitchOoM/socket) `Transport` that *returns* a `ByteStream` whose bytes ride binary WebSocket frames — so WebSocket can be the always-reachable floor of a fallback chain (QUIC → WebTransport → TCP → **WebSocket**) when hostile middleboxes block everything but `:443`.

```kotlin
import com.ditchoom.websocket.tcp.WebSocketTransport

val transport = WebSocketTransport(
    websocketEndpoint = "/tunnel",
    underlying = TcpTransport(),      // the WS handshake rides this transport
)

// Slots into any socket API that takes a Transport (e.g. a FallbackTransport chain):
val stream: ByteStream = transport.connect(host, port, config)
stream.write(payload)                 // one binary WebSocket frame per write
```

## Compression

Request permessage-deflate (RFC 7692) — negotiated during the handshake, transparent thereafter:

```kotlin
val options = WebSocketConnectionOptions(
    name = "example.com",
    requestCompression = true,
    compressionOptions = CompressionOptions(
        clientNoContextTakeover = false,  // keep the LZ77 window across messages (better ratio)
        serverNoContextTakeover = false,
        clientMaxWindowBits = 15,         // 0 = don't request (platform default)
    ),
)
```

## Error handling

`connect*` and `send` throw the sealed `WebSocketException`:

```kotlin
try {
    connectTcpWebSocket(options).use { ws -> /* ... */ }
} catch (e: WebSocketException.HandshakeRejected) {
    println("server rejected the upgrade: HTTP ${e.statusCode}")
} catch (e: WebSocketException.TransportFailed) {
    println("network/TLS failure: ${e.cause?.message}")
} catch (e: WebSocketException.ProtocolViolation) {
    println("peer broke RFC 6455: ${e.message}")
} catch (e: WebSocketException.ConnectionClosed) {
    println("closed: code=${e.code} reason=${e.reason}")
}
```

## Choosing an entry point

| You have… | Call | Module |
|-----------|------|--------|
| A host + port, on JVM/Android/Apple/Linux/Node.js | `connectTcpWebSocket(options)` | `websocket-tcp` |
| An Apple app wanting system-managed TLS/proxy | `connectAppleNativeWebSocket(options)` | `websocket-apple` |
| Browser JS | `connectBrowserWebSocket(options)` | `websocket` (jsMain) |
| A pre-connected `ByteStream` (custom transport, QUIC, test double) | `connectWebSocket(stream, options)` | `websocket` |

## Platform Support

| Platform | Transport | Compression |
|----------|-----------|-------------|
| JVM 1.8+ / Android | NIO2 `AsynchronousSocketChannel` | context takeover, `max_window_bits=15` |
| iOS / macOS / tvOS / watchOS | `NWConnection` (Network.framework) or `NSURLSessionWebSocketTask` | context takeover |
| Linux x64 / ARM64 | io_uring or epoll, direct zlib | context takeover + custom window bits |
| Node.js | raw socket, RFC 6455 engine | no context takeover |
| Browser | native `WebSocket` | handled by the browser |

## Part of the DitchOoM stack

WebSocket sits on top of [`socket`](https://github.com/DitchOoM/socket) (TCP + TLS + transport fallback) and [`buffer`](https://github.com/DitchOoM/buffer) (zero-copy `ReadBuffer`/`WriteBuffer` + codecs), and composes both ways — consuming a `ByteStream` to speak WebSocket, or exposing one as a fallback `Transport`.

```
┌─────────────────────────────┐
│  Your app / protocol        │
├─────────────────────────────┤
│  websocket (+ your Codec<B>) │  ← com.ditchoom:websocket(-tcp / -apple)
├─────────────────────────────┤
│  socket (TCP + TLS + fallback)  ← com.ditchoom:socket   (WebSocketTransport plugs in here)
├─────────────────────────────┤
│  buffer (+ buffer-codec)    │  ← com.ditchoom:buffer
└─────────────────────────────┘
```

## License

    Copyright 2022 DitchOoM

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

[docs]: https://ditchoom.github.io/websocket/
