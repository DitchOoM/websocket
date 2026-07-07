---
id: websocket-as-transport
title: WebSocket as a Transport
sidebar_position: 2
---

# WebSocket as a Transport

`connectWebSocket` *takes* a byte stream and gives you WebSocket messages. `WebSocketTransport` is the
**inverse**: it *is* a `socket` `Transport` that *returns* a `ByteStream` whose bytes ride binary
WebSocket frames. That flip is what lets WebSocket be the always-reachable floor of a transport
fallback chain — when hostile middleboxes block QUIC, WebTransport, and even plain TCP, a `wss://:443`
connection usually still gets through.

```
QUIC  →  WebTransport  →  TCP  →  WebSocket    (fall down the list until one connects)
```

## The transport

```kotlin
import com.ditchoom.websocket.tcp.WebSocketTransport

val transport = WebSocketTransport(
    websocketEndpoint = "/tunnel",
    protocols = emptyList(),
    requestCompression = false,
    compressionOptions = CompressionOptions(),
    underlying = TcpTransport(),      // the WS handshake rides this transport; TLS follows config.tls
)

// A Transport exposes exactly one method:
val stream: ByteStream = transport.connect(host, port, config)
```

Per-connection addressing (endpoint, subprotocols, compression) is fixed at **construction**, so
`connect()` only takes the uniform `(hostname, port, config)` surface every other transport uses —
which is precisely what makes it substitutable in a fallback chain.

## Byte-stream semantics

The returned `ByteStream` tunnels raw bytes over WebSocket, transparently to whatever protocol runs
on top:

- `write(buffer)` → **one binary WebSocket frame** per call
- `read()` → the next data payload (`ReadResult.Data`); text frames surface as their UTF-8 bytes
- Ping/Pong are invisible — auto-answered by the connection
- A peer Close or EOF surfaces as `ReadResult.End`

```kotlin
stream.write(requestBytes)
val response = stream.read()          // ReadResult.Data / ReadResult.End
```

## In a fallback chain

Because it implements the same `Transport` interface as `TcpTransport` (and the QUIC/WebTransport
rungs), `WebSocketTransport` drops into a `socket` `FallbackTransport` as the last resort:

```kotlin
// Sketch — see the socket docs for the exact FallbackTransport API.
val transport = FallbackTransport(
    QuicTransport(),
    WebTransportTransport(),
    TcpTransport(),
    WebSocketTransport(websocketEndpoint = "/tunnel"),   // universal floor
)
val stream = transport.connect(host, 443, config)
```

Anything that speaks to a `ByteStream` — an MQTT client, a custom RPC framing, a database protocol —
now runs unchanged over whichever rung connects. See the
[socket documentation](https://ditchoom.github.io/socket/) for building fallback chains, and
[MQTT over WebSocket](../guides/mqtt-over-websocket.md) for a concrete consumer.
