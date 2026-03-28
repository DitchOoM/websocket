---
id: error-handling
title: Error Handling
sidebar_position: 2
---

# Error Handling

## Exception Hierarchy

All WebSocket-specific errors are subtypes of the sealed `WebSocketException` class:

```
WebSocketException (sealed)
├── TransportFailed      - TCP/TLS failure
├── HandshakeRejected    - HTTP upgrade rejected
├── ProtocolViolation    - RFC 6455 violation
└── ConnectionClosed     - WebSocket close with code/reason
```

This lets you catch errors at the right granularity without importing socket-level types:

```kotlin
try {
    client.connect()
    // ... use client ...
} catch (e: WebSocketException.TransportFailed) {
    // Network issue: DNS failure, connection refused, TLS error
    // Original socket exception available in e.cause
} catch (e: WebSocketException.HandshakeRejected) {
    // Server returned non-101 status or invalid handshake
    // HTTP status code available in e.statusCode
} catch (e: WebSocketException) {
    // Catch-all for any WebSocket error
}
```

## TransportFailed

Wraps socket-level errors (DNS, TCP, TLS). The original `SocketException` is in `cause`:

```kotlin
catch (e: WebSocketException.TransportFailed) {
    when (val cause = e.cause) {
        is SocketUnknownHostException -> // DNS resolution failed
        is SocketConnectionException.Refused -> // Server not listening
        is SSLHandshakeFailedException -> // TLS certificate error
        is SocketTimeoutException -> // Connection timed out
    }
}
```

## HandshakeRejected

The server responded to the HTTP upgrade but the response was invalid:

```kotlin
catch (e: WebSocketException.HandshakeRejected) {
    when (e.statusCode) {
        401 -> // Authentication required
        403 -> // Forbidden
        404 -> // Endpoint not found
        null -> // Invalid handshake (wrong accept key, missing headers)
    }
}
```

## ProtocolViolation

The server sent data that violates RFC 6455:

- Invalid UTF-8 in a text frame
- Reserved opcode used
- Fragmentation protocol violation
- Control frame too large (> 125 bytes)

## ConnectionClosed

The connection was closed with a WebSocket close code:

```kotlin
catch (e: WebSocketException.ConnectionClosed) {
    when (e.code?.toInt()) {
        1000 -> // Normal closure
        1001 -> // Going away
        1002 -> // Protocol error
        1003 -> // Unsupported data
        1006 -> // Abnormal closure (no close frame)
        1011 -> // Server error
    }
}
```

## Disconnected State

The `ConnectionState.Disconnected` state carries the same error information:

```kotlin
client.connectionState.collect { state ->
    if (state is ConnectionState.Disconnected) {
        val error = state.t  // Throwable? (often a WebSocketException)
        val code = state.code  // UShort? (WebSocket close code)
        val reason = state.reason  // String? (close reason)
    }
}
```
