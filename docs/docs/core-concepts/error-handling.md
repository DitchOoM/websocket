---
id: error-handling
title: Error Handling
sidebar_position: 2
---

# Error Handling

All WebSocket-specific failures are subtypes of the sealed `WebSocketException`, so you can catch at
exactly the granularity you need without reaching for socket-level types:

```
WebSocketException (sealed)
├── TransportFailed      - TCP/TLS failure (original socket exception in .cause)
├── HandshakeRejected    - HTTP upgrade rejected (.statusCode)
├── ProtocolViolation    - peer broke RFC 6455
└── ConnectionClosed     - closed with a WebSocket code/reason (.code, .reason)
```

`connect*` and `send` are the throwing surfaces:

```kotlin
try {
    connectTcpWebSocket(options).use { ws ->
        ws.send(WebSocketMessage.Text("hello"))
        ws.receive().collect { /* ... */ }
    }
} catch (e: WebSocketException.TransportFailed) {
    // Network/TLS: DNS failure, connection refused, certificate error, timeout.
    // The underlying socket exception is in e.cause.
} catch (e: WebSocketException.HandshakeRejected) {
    // Server answered the upgrade with a non-101 status or an invalid handshake.
} catch (e: WebSocketException) {
    // Catch-all for any WebSocket error.
}
```

## TransportFailed

Wraps a socket-level failure (DNS, TCP, TLS). Inspect `e.cause` for the concrete `socket` exception:

```kotlin
catch (e: WebSocketException.TransportFailed) {
    when (e.cause) {
        // e.g. SocketUnknownHostException     -> DNS resolution failed
        // e.g. SSLHandshakeFailedException    -> TLS certificate/handshake error
        // e.g. a connection-refused / timeout -> server not listening / slow network
        else -> log(e.cause)
    }
}
```

## HandshakeRejected

The server responded to the HTTP upgrade, but not with a valid `101`. `statusCode` is the HTTP
status when there was one, or `null` for a malformed handshake (bad `Sec-WebSocket-Accept`, missing
headers):

```kotlin
catch (e: WebSocketException.HandshakeRejected) {
    when (e.statusCode) {
        401 -> // authentication required
        403 -> // forbidden
        404 -> // endpoint not found
        null -> // invalid handshake
        else -> // other non-101 response
    }
}
```

## ProtocolViolation

The peer sent something that violates RFC 6455 — invalid UTF-8 in a text frame, a reserved opcode, a
fragmentation violation, or an oversized control frame (> 125 bytes). Retrying won't help; it's an
implementation mismatch.

## ConnectionClosed

Thrown by `send` when the connection is already closed. It carries the WebSocket close `code` and
`reason` when the peer initiated the close:

```kotlin
catch (e: WebSocketException.ConnectionClosed) {
    when (e.code?.toInt()) {
        1000 -> // normal closure
        1001 -> // going away
        1002 -> // protocol error
        1006 -> // abnormal closure (no close frame)
        1011 -> // server error
        else -> // other / null
    }
}
```

You also observe a graceful peer close as a `WebSocketMessage.Close(code, reason)` emitted from
`receive()` before the flow completes — see [Connection Lifecycle](connection-lifecycle.md). Use the
exception path for `send`-after-close and the message path for orderly shutdown.
