---
id: getting-started
title: Getting Started
sidebar_position: 2
---

# Getting Started

## Basic Usage

Connect to a WebSocket server, send a message, and receive the echo:

```kotlin
val options = WebSocketConnectionOptions(
    name = "echo.websocket.org",
    port = 443,
    tls = true,
    websocketEndpoint = "/.ws",
)

val client = WebSocketClient.allocate(options)
client.connect()

// Send a text message
client.write("Hello, WebSocket!")

// Receive the echo
val echo = client.incomingTextMessages.first()
println("Received: $echo")

client.close()
```

## Connection Options

Configure the connection with `WebSocketConnectionOptions`:

```kotlin
val options = WebSocketConnectionOptions(
    name = "example.com",           // Hostname
    port = 443,                     // Port (443 enables TLS by default)
    tls = true,                     // Enable TLS (or use TlsConfig for fine-grained control)
    websocketEndpoint = "/ws",      // WebSocket path
    protocols = listOf("mqtt"),     // Subprotocol negotiation
    connectionTimeout = 10.seconds, // Connection timeout
    readTimeout = 10.seconds,       // Read timeout
    writeTimeout = 10.seconds,      // Write timeout
    requestCompression = true,      // Enable permessage-deflate
)
```

## Receiving Messages

Messages are exposed as Kotlin Flows. Use typed flows to avoid casting:

```kotlin
val client = WebSocketClient.allocate(options)
client.connect()

// Option 1: All message types
client.incomingMessages.collect { message ->
    when (message) {
        is WebSocketMessage.Text -> println("Text: ${message.value}")
        is WebSocketMessage.Binary -> println("Binary: ${message.value.remaining()} bytes")
        is WebSocketMessage.Ping -> println("Ping received")
        is WebSocketMessage.Pong -> println("Pong received")
        is WebSocketMessage.Close -> println("Close: ${message.code} ${message.reason}")
    }
}

// Option 2: Text messages only (more efficient)
client.incomingTextMessages.collect { text ->
    println("Text: $text")
}

// Option 3: Binary messages only
client.incomingBinaryMessages.collect { buffer ->
    println("Binary: ${buffer.remaining()} bytes")
}
```

## Connection State

Track connection lifecycle with `connectionState`:

```kotlin
client.connectionState.collect { state ->
    when (state) {
        ConnectionState.Initialized -> println("Ready to connect")
        ConnectionState.Connecting -> println("Connecting...")
        ConnectionState.Connected -> println("Connected!")
        is ConnectionState.Disconnected -> {
            println("Disconnected: ${state.reason}")
            state.t?.let { println("Error: ${it.message}") }
        }
    }
}
```

## Error Handling

Catch `WebSocketException` subtypes for specific error handling:

```kotlin
try {
    client.connect()
} catch (e: WebSocketException.HandshakeRejected) {
    println("Server rejected: ${e.statusCode}")
} catch (e: WebSocketException.TransportFailed) {
    println("Network error: ${e.cause.message}")
} catch (e: WebSocketException.ProtocolViolation) {
    println("Protocol error: ${e.message}")
} catch (e: WebSocketException.ConnectionClosed) {
    println("Closed: code=${e.code}, reason=${e.reason}")
}
```

## Auto-Reconnection

Use `ReconnectingWebSocketClient` for automatic reconnection with backoff:

```kotlin
val client = ReconnectingWebSocketClient(
    connectionOptions = options,
    classifier = WebSocketReconnectionClassifier(),
)

client.connect() // Starts reconnection loop

// Messages from all connections are merged into a single flow
client.incomingTextMessages.collect { text ->
    println("Received: $text")
}
```

The classifier determines retry behavior:
- **Give up** on: handshake rejection, protocol violations, normal close (code 1000)
- **Retry with backoff** on: transport failures, abnormal disconnects
- **Never retry** on: DNS failures, TLS certificate errors

## Sending Binary Data

```kotlin
val buffer = BufferFactory.allocate(1024)
buffer.writeString("binary payload", Charset.UTF8)
buffer.resetForRead()

client.write(buffer)
```

## Ping/Pong

```kotlin
if (client.isPingSupported()) {
    client.ping() // Empty ping
    client.ping(payloadBuffer) // Ping with payload
}

// Pong responses arrive in incomingMessages
client.incomingMessages.filterIsInstance<WebSocketMessage.Pong>().collect { pong ->
    println("Pong: ${pong.value.remaining()} bytes")
}
```
