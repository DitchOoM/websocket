---
id: jvm
title: JVM & Android
sidebar_position: 1
---

# JVM & Android

## Implementation

On JVM and Android, the library uses `DefaultWebSocketClient` — a full RFC 6455 implementation built on `com.ditchoom:socket` (Java NIO / Android sockets).

## TLS

TLS is handled by the underlying socket library. On port 443, TLS is enabled by default:

```kotlin
// TLS enabled automatically
val options = WebSocketConnectionOptions(name = "example.com", port = 443)

// Explicit TLS configuration
val options = WebSocketConnectionOptions(
    name = "example.com",
    port = 8443,
    tls = TlsConfig(
        verifyCertificates = true,
        verifyHostname = true,
    ),
)

// Disable TLS
val options = WebSocketConnectionOptions(name = "example.com", port = 80, tls = false)
```

## Compression

JVM supports `permessage-deflate` with context takeover but does not support custom window bits (Java's `Deflater` hardcodes `MAX_WBITS=15`).

## Dependencies

The JVM target pulls in:
- `com.ditchoom:buffer-jvm` - `DirectByteBuffer`-backed buffers
- `com.ditchoom:socket-jvm` - Java NIO socket implementation
