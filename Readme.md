WebSocket
==========

See the [project website][docs] for documentation and APIs.

WebSocket is a Kotlin Multiplatform library providing RFC 6455 compliant WebSocket client
functionality with permessage-deflate compression (RFC 7692).

- **JVM/Android**: Built on `AsynchronousSocketChannel` (NIO2)
- **iOS/macOS/tvOS/watchOS**: `NWConnection` via Network.framework
- **Linux**: io_uring or epoll with direct zlib
- **Node.js/Browser**: Native WebSocket API

## Features

- **Suspend-based API**: All I/O operations are coroutine-friendly suspend functions
- **Flow-based messages**: Receive messages via `incomingMessages: Flow<WebSocketMessage>`
- **Compression**: permessage-deflate with context takeover and custom window bits
- **Zero-copy frame pipeline**: Direct buffer I/O with SIMD-optimized masking
- **Full RFC 6455 compliance**: Passes all 517 Autobahn test cases

## Installation

[![Maven Central](https://img.shields.io/maven-central/v/com.ditchoom/websocket.svg)](https://central.sonatype.com/artifact/com.ditchoom/websocket)

```kotlin
dependencies {
    implementation("com.ditchoom:websocket:<latest-version>")
}
```

Find the latest version on [Maven Central](https://central.sonatype.com/artifact/com.ditchoom/websocket).

## Quick Example

```kotlin
val options = WebSocketConnectionOptions(
    name = "echo.websocket.org",
    port = 443,
    tls = true,
)
val client = WebSocketClient.allocate(options).connect()

// Send a message
client.write("Hello, WebSocket!")

// Receive messages
client.incomingMessages.collect { message ->
    when (message) {
        is WebSocketMessage.Text -> println("Received: ${message.value}")
        is WebSocketMessage.Binary -> println("Binary: ${message.value.remaining()} bytes")
    }
}

client.close()
```

## Compression

Request permessage-deflate compression for reduced bandwidth:

```kotlin
val options = WebSocketConnectionOptions(
    name = "example.com",
    requestCompression = true,
    compressionOptions = CompressionOptions(
        clientNoContextTakeover = false, // maintain LZ77 window across messages
        serverNoContextTakeover = false,
    ),
)
```

## Platform Support

| Platform | Implementation | Compression |
|----------|---------------|-------------|
| JVM 1.8+ | NIO2 AsyncSocketChannel | Context takeover, max_window_bits=15 |
| Android | Same as JVM | Same as JVM |
| iOS/macOS/tvOS/watchOS | NWConnection | Context takeover |
| Linux x64/ARM64 | io_uring / epoll | Context takeover + custom window bits |
| Node.js | Native WebSocket | No context takeover |
| Browser | Native WebSocket | Handled by browser |

## License

    Copyright 2022 DitchOoM

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

[docs]: https://ditchoom.github.io/websocket/
