---
id: reconnection
title: Reconnection
sidebar_position: 4
---

# Reconnection

## ReconnectingWebSocketClient

Wraps `WebSocketClient` with automatic reconnection logic:

```kotlin
val client = ReconnectingWebSocketClient(
    connectionOptions = options,
    classifier = WebSocketReconnectionClassifier(),
)

client.connect()

// Messages from all connections are merged into a single flow
client.incomingTextMessages.collect { text ->
    processMessage(text)
}
```

On disconnection, the classifier decides whether to retry. If yes, a fresh `WebSocketClient` is created and connected. All message flows are merged across reconnections.

## Reconnection Classifier

`WebSocketReconnectionClassifier` makes intelligent retry decisions based on the error type:

| Error | Decision | Rationale |
|-------|----------|-----------|
| `HandshakeRejected` | Give up | Server explicitly rejected; retrying won't help |
| `ProtocolViolation` | Give up | Implementation mismatch; retrying won't help |
| `ConnectionClosed(1000)` | Give up | Normal closure; intentional disconnect |
| `ConnectionClosed(other)` | Retry | Abnormal close; may be transient |
| `TransportFailed` | Delegate | Depends on underlying socket error |
| DNS failure | Give up | Host doesn't exist |
| TLS certificate error | Give up | Security configuration issue |
| Connection refused | Retry with backoff | Server may be restarting |
| Timeout | Retry with backoff | Network congestion |

## Custom Classifier

Implement `ReconnectionClassifier` for custom retry logic:

```kotlin
class MyClassifier : ReconnectionClassifier {
    override suspend fun classify(error: Throwable): ReconnectionDecision {
        return when {
            isBusinessHours() -> RetryWithBackoff(delay = 5.seconds)
            else -> GiveUp
        }
    }

    fun reset() { /* Reset backoff state */ }
}

val client = ReconnectingWebSocketClient(
    connectionOptions = options,
    classifier = MyClassifier(),
)
```
