---
id: connection-lifecycle
title: Connection Lifecycle
sidebar_position: 1
---

# Connection Lifecycle

A `Connection<WebSocketMessage<B>>` has a simple, explicit lifecycle: you `connect`, `send`/`receive`
while it's live, and `close`. There is no separate "state" object on the base connection — the state
*is* whether `connect*` returned, whether `receive()` is still emitting, and whether `close()` has
run. (For an observable state machine across reconnects, see [Reconnection](reconnection.md).)

## Connecting

`connect*` suspends and only returns once the connection is fully established:

1. TCP connect (and TLS handshake, when `tls`)
2. RFC 6455 HTTP `Upgrade` request + `101 Switching Protocols` response, validated
3. permessage-deflate negotiated (if `requestCompression`)

If any step fails it throws a [`WebSocketException`](error-handling.md); it never returns a
half-open connection. On success the read loop is already running.

```kotlin
val ws = connectTcpWebSocket(options)   // live on return
```

## Sending & receiving

While live, `send(message)` writes a frame and `receive()` emits inbound messages as a `Flow`:

```kotlin
ws.send(WebSocketMessage.Text("hello"))

ws.receive().collect { message -> /* ... */ }   // suspends until the connection closes
```

Ping frames are answered with a Pong automatically; you still see them in `receive()` if you want to
observe them. `send`/`receive` are not thread-safe — confine each to its own coroutine.

## Closing

`close()` performs the RFC 6455 **close handshake**:

1. Sends a Close frame (code `1000` by default) if still connected
2. Waits — bounded by a 5-second timeout — for the peer's Close frame or EOF, so in-flight writes
   drain instead of being cut off
3. Tears down the transport, cancels the read loop, and releases pooled buffers

The `receive()` flow **completes** when the connection closes, so a `collect` naturally ends. Calling
`close()` again is a no-op.

Use the `use { }` extension to close deterministically even on exceptions — the idiomatic pattern:

```kotlin
connectTcpWebSocket(options).use { ws ->
    ws.send(WebSocketMessage.Text("hello"))
    val reply = ws.receive().first()
    // ... close() runs here, in a finally
}
```

## Server-initiated close

When the peer closes:

1. A `WebSocketMessage.Close(code, reason)` is emitted from `receive()` (the last message)
2. The `receive()` flow completes
3. The connection is torn down; a later `close()` is a no-op

```kotlin
ws.receive().collect { message ->
    if (message is WebSocketMessage.Close) {
        println("server closed: ${message.code} ${message.reason}")
    }
}
// ...flow completed — the connection is gone
```

## Observing state across reconnects

The base connection is single-shot: once closed, it's done. To observe `Connecting` / `Connected` /
`Disconnected` transitions across automatic reconnects, wrap it in `socket`'s
`ReconnectingConnection`, which exposes a `state: StateFlow<ConnectionState>`. See
[Reconnection](reconnection.md).
