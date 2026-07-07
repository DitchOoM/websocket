---
id: reconnection
title: Reconnection
sidebar_position: 4
---

# Reconnection

There's no WebSocket-specific reconnecting client — and there doesn't need to be. Reconnection lives
in the `socket` library as **`ReconnectingConnection<T>`**, which wraps *any* `suspend () -> Connection<T>`
with network-aware backoff. Because a WebSocket connection is just a `Connection<WebSocketMessage<B>>`,
it composes directly:

```kotlin
import com.ditchoom.socket.transport.ReconnectingConnection
import com.ditchoom.socket.DefaultReconnectionClassifier

val conn = ReconnectingConnection(
    connect = { connectTcpWebSocket(options) },        // re-invoked on each reconnect
    classifier = DefaultReconnectionClassifier(),
)

// ReconnectingConnection is itself a Connection<T> — same send/receive/close API.
// A single receive() flow survives reconnects; messages from every underlying
// connection are merged into it.
conn.receive().collect { message ->
    when (message) {
        is WebSocketMessage.Text -> handle(message.payload)
        else -> {}
    }
}
```

Your `connect` lambda is the reconnection unit: run *everything* a fresh session needs inside it
(connect, and any app-level handshake such as an MQTT `CONNECT`), so each reconnect re-establishes a
fully-ready connection.

## Network-aware backoff

`ReconnectingConnection` doesn't just sleep between attempts. Via its `monitorFactory` (default
`NetworkMonitor.default()`, the platform's best reactive monitor) it:

- **resets backoff** when the network becomes available again, reconnecting immediately instead of
  waiting out the current delay;
- **races the backoff against path changes** — Wi-Fi returning, cellular taking over, a captive
  portal clearing — abandoning the remaining delay to retry at once.

Pass `monitorFactory = { NetworkMonitor.AlwaysAvailable }` to opt out and get a plain backoff. It
also exposes observable state:

```kotlin
conn.state.collect { println(it) }                 // StateFlow<ConnectionState>
conn.lastMessageReceived.value                      // timestamp of the last decoded message
```

## Deciding when to retry

A `ReconnectionClassifier` maps the failure to a `ReconnectDecision`: `RetryAfter(delay)` or
`GiveUp`. `DefaultReconnectionClassifier()` gives up on permanent failures (host doesn't exist,
normal close) and retries transient ones with backoff. It's a `fun interface`, so a custom policy is
a lambda:

```kotlin
import com.ditchoom.socket.ReconnectionClassifier
import com.ditchoom.socket.ReconnectDecision
import kotlin.time.Duration.Companion.seconds

val classifier = ReconnectionClassifier { error ->
    when (error) {
        is WebSocketException.HandshakeRejected -> ReconnectDecision.GiveUp       // server said no
        is WebSocketException.ProtocolViolation -> ReconnectDecision.GiveUp       // implementation mismatch
        else                                    -> ReconnectDecision.RetryAfter(5.seconds)
    }
}

val conn = ReconnectingConnection(
    connect = { connectTcpWebSocket(options) },
    classifier = classifier,
)
```

See the [socket documentation](https://ditchoom.github.io/socket/) for the full `ReconnectingConnection`
surface, including the `liveness` seam that tears down a half-open connection on a path change so
reconnection starts promptly instead of waiting for the OS TCP timeout.
