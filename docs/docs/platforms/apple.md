---
id: apple
title: Apple (iOS, macOS, tvOS, watchOS)
sidebar_position: 2
---

# Apple Platforms

## Implementation

On Apple platforms, the library provides two WebSocket implementations:

1. **`DefaultWebSocketClient`** (default) - Full RFC 6455 implementation with compression support
2. **`NativeWebSocketConnection`** - Apple Network.framework (`NWConnection` + `nw_protocol_websocket`) for lightweight connections

## Default Client

The standard `WebSocketClient.allocate()` returns `DefaultWebSocketClient`, which provides the full feature set including `permessage-deflate` compression:

```kotlin
val client = WebSocketClient.allocate(options)
client.connect()
```

## Native WebSocket Connection

For lightweight use cases that don't need compression, use the platform-native implementation directly:

```kotlin
val native = connectNativeWebSocket(
    url = "wss://example.com/ws",
    tls = true,
    verifyCertificates = true,
    autoReplyPing = true,
    subprotocols = listOf("mqtt"),
    timeoutSeconds = 30,
)

// Lower-level API using opcodes
val message = native.receiveMessage()
when (message.opcode) {
    NativeWebSocketConnection.OPCODE_TEXT -> // text
    NativeWebSocketConnection.OPCODE_BINARY -> // binary
    NativeWebSocketConnection.OPCODE_CLOSE -> // close
}

native.close()
```

The native implementation uses Apple's Network.framework which handles TLS, HTTP upgrade, and frame parsing at the OS level.

## Compression

Apple platforms support `permessage-deflate` with context takeover via zlib but do not support custom window bits through the DefaultWebSocketClient. The native `NWConnection` implementation delegates compression to the OS.
