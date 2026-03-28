---
id: linux
title: Linux
sidebar_position: 3
---

# Linux

## Implementation

On Linux (Kotlin/Native), the library uses `DefaultWebSocketClient` with `com.ditchoom:socket` (io_uring or POSIX sockets with OpenSSL for TLS).

## Compression

Linux has the most complete compression support of any platform:

- Custom `deflateInit2` window bits (8-15)
- Context takeover (maintains LZ77 sliding window across messages)
- Direct zlib integration via cinterop

## Memory Management

On Linux K/N, buffers use `malloc`/`free` (no GC). The library manages this via:

- `BufferPool` for buffer reuse
- Automatic cleanup in the read loop's `finally` block
- `freeIfNeeded()` calls after buffer consumption

## TLS

TLS is provided by OpenSSL via the socket library. Certificate verification is enabled by default:

```kotlin
val options = WebSocketConnectionOptions(
    name = "example.com",
    port = 443,
    tls = TlsConfig(
        verifyCertificates = true,
    ),
)
```
