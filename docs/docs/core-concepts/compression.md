---
id: compression
title: Compression
sidebar_position: 3
---

# Compression

The library supports the `permessage-deflate` WebSocket extension (RFC 7692) for compressing messages.

## Enabling Compression

```kotlin
val options = WebSocketConnectionOptions(
    name = "example.com",
    port = 443,
    tls = true,
    requestCompression = true,
)
```

The client will include `Sec-WebSocket-Extensions: permessage-deflate` in the handshake. Compression is only active if the server agrees.

## Compression Options

Fine-tune compression behavior with `CompressionOptions`:

```kotlin
val options = WebSocketConnectionOptions(
    name = "example.com",
    requestCompression = true,
    compressionOptions = CompressionOptions(
        clientNoContextTakeover = true,   // Reset compressor per message
        serverNoContextTakeover = true,   // Request server reset per message
        serverMaxWindowBits = 15,         // Server decompression window (0 = default)
        clientMaxWindowBits = 15,         // Client compression window (0 = default)
    ),
)
```

## Context Takeover

Context takeover allows the compressor to maintain its LZ77 sliding window across messages, improving compression ratio for related messages. When disabled (`noContextTakeover = true`), each message is compressed independently.

**Trade-off**: Context takeover gives better compression but uses more memory and requires both sides to process messages in order.

## Platform Capabilities

Not all platforms support all compression features:

| Platform | Custom Window Bits | Context Takeover |
|----------|-------------------|------------------|
| Linux K/N | Yes | Yes |
| JVM/Android | No (hardcoded 15) | Yes |
| Apple | No | Yes |
| JS (Node.js) | No | No |
| JS (Browser) | N/A (browser handles compression) | N/A |

## How It Works

1. **Handshake**: Client offers `permessage-deflate` with configured parameters
2. **Negotiation**: Server may accept, modify, or reject the extension
3. **Compression**: Outgoing messages are deflated before framing
4. **Decompression**: Incoming compressed messages are inflated after frame reassembly
5. **Sync flush**: Each compressed message ends with the sync flush marker (`0x00 0x00 0xFF 0xFF`) per RFC 7692
