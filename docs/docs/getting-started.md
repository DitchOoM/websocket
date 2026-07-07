---
id: getting-started
title: Getting Started
sidebar_position: 2
---

# Getting Started

Every entry point returns a `Connection<WebSocketMessage<B>>` — a small interface with three
members: `send`, `receive` (a `Flow`), and `close`. You pick how to connect based on your platform;
the message API is identical everywhere.

## Install

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            // TCP + TLS + WebSocket for JVM, Android, Apple, Linux, and Node.js:
            implementation("com.ditchoom:websocket-tcp:<latest-version>")
        }
    }
}
```

See [Choosing an entry point](#choosing-an-entry-point) for the browser and Apple-native modules.

## Connect, send, receive

```kotlin
import com.ditchoom.websocket.WebSocketConnectionOptions
import com.ditchoom.websocket.WebSocketMessage
import com.ditchoom.websocket.use
import com.ditchoom.websocket.tcp.connectTcpWebSocket
import kotlinx.coroutines.flow.first

val options = WebSocketConnectionOptions(
    name = "echo.websocket.org",
    port = 443,
    tls = true,                 // defaults to (port == 443)
    websocketEndpoint = "/",
)

connectTcpWebSocket(options).use { ws ->
    ws.send(WebSocketMessage.Text("Hello, WebSocket!"))

    val reply = ws.receive().first()
    println((reply as WebSocketMessage.Text).payload)
}
```

`connectTcpWebSocket` suspends until the TCP/TLS connection is up, the HTTP upgrade has completed,
and permessage-deflate has been negotiated — the returned connection is live. `use { }` runs your
block and then `close()`s the connection (the RFC 6455 close handshake) in a `finally`.

> `send` and `receive` are **not** thread-safe. Send from one coroutine and collect `receive()` in
> another; don't share a single connection across concurrent senders.

## Receiving messages

`receive()` is a `Flow<WebSocketMessage<B>>`. `WebSocketMessage` is a sealed interface, so a `when`
is exhaustive with no casting:

```kotlin
ws.receive().collect { message ->
    when (message) {
        is WebSocketMessage.Text   -> println("text: ${message.payload}")   // always a UTF-8 String
        is WebSocketMessage.Binary -> handle(message.payload)               // payload: B (see Codecs)
        is WebSocketMessage.Ping   -> println("ping: ${message.appData}")   // auto-ponged for you
        is WebSocketMessage.Pong   -> println("pong: ${message.appData}")
        is WebSocketMessage.Close  -> println("close ${message.code}: ${message.reason}")
    }
}
```

The flow completes when the connection closes (peer Close frame, EOF, or your own `close()`), so a
`collect` on the connection is a natural read loop.

## Sending messages

`send` takes the same sealed `WebSocketMessage`:

```kotlin
ws.send(WebSocketMessage.Text("a text frame"))
ws.send(WebSocketMessage.Ping("keepalive"))              // appData ≤ 125 bytes
ws.send(WebSocketMessage.Close(code = 1000u, reason = "done"))
```

Binary sends carry your codec's type — see [Typed binary messages](#typed-binary-messages). With the
default `EmptyCodec` the binary payload type is `Unit`, so use a codec (or `BinaryPassThroughCodec`)
when you need to send bytes.

## Connection options

`WebSocketConnectionOptions` is a `data class`:

```kotlin
val options = WebSocketConnectionOptions(
    name = "example.com",             // host (required)
    port = 443,                       // default 443
    tls = true,                       // default: port == 443
    websocketEndpoint = "/ws",        // request path, default "/"
    protocols = listOf("mqtt"),       // Sec-WebSocket-Protocol, default empty
    connectionTimeout = 15.seconds,   // default 15s
    readTimeout = 15.seconds,         // default: connectionTimeout
    writeTimeout = 15.seconds,        // default: connectionTimeout
    requestCompression = true,        // permessage-deflate, default false
    // compressionOptions = CompressionOptions(...),  // see Compression
    // bufferFactory = BufferFactory.deterministic(), // default; see below
)
```

`bufferFactory` defaults to `BufferFactory.deterministic()` (off-heap, zero-copy, deterministic
cleanup). If you pass a shared `BufferPool`, it must be `ThreadingMode.MultiThreaded` or the connect
fails fast with `IllegalArgumentException`.

## Typed binary messages

Text frames are always `String`. Binary frames run a `com.ditchoom.buffer.codec.Codec<B>` that you
supply, so `receive()` hands you your own decoded type instead of raw bytes:

```kotlin
val ws: Connection<WebSocketMessage<SensorReading>> =
    connectTcpWebSocket(options, binaryCodec = SensorReadingCodec)

ws.send(WebSocketMessage.Binary(SensorReading(id = 7u, celsius = 21)))

ws.receive().collect { msg ->
    if (msg is WebSocketMessage.Binary) update(msg.payload)   // payload: SensorReading
}
```

See [Typed messages with codecs](recipes/typed-messages-with-codecs.md) for the built-in codecs and
how to write your own.

## Error handling

`connect*` and `send` throw the sealed `WebSocketException`:

```kotlin
try {
    connectTcpWebSocket(options).use { ws -> /* ... */ }
} catch (e: WebSocketException.HandshakeRejected) {
    println("upgrade rejected: HTTP ${e.statusCode}")
} catch (e: WebSocketException.TransportFailed) {
    println("network/TLS failure: ${e.cause?.message}")
} catch (e: WebSocketException.ProtocolViolation) {
    println("peer broke RFC 6455: ${e.message}")
} catch (e: WebSocketException.ConnectionClosed) {
    println("closed: code=${e.code} reason=${e.reason}")
}
```

See [Error handling](core-concepts/error-handling.md) for the full hierarchy.

## Auto-reconnection

Reconnection lives in the `socket` library as `ReconnectingConnection<T>`, which wraps *any*
`Connection<T>` — including a WebSocket one — with network-aware backoff. See
[Reconnection](core-concepts/reconnection.md).

## Choosing an entry point

| You have… | Call | Module |
|-----------|------|--------|
| A host + port, on JVM/Android/Apple/Linux/Node.js | `connectTcpWebSocket(options)` | `websocket-tcp` |
| An Apple app wanting system-managed TLS/proxy | `connectAppleNativeWebSocket(options)` | `websocket-apple` |
| Browser JS | `connectBrowserWebSocket(options)` | `websocket` (jsMain) |
| A pre-connected `ByteStream` (custom transport, test double) | `connectWebSocket(stream, options)` | `websocket` |

All four have the same shape — an optional `binaryCodec: Codec<B>` and `parentScope: CoroutineScope?`
— and all return `Connection<WebSocketMessage<B>>`, so the rest of your code is identical regardless
of platform.
