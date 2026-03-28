---
id: javascript
title: JavaScript
sidebar_position: 4
---

# JavaScript

## Node.js

On Node.js, the library uses `DefaultWebSocketClient` — the full RFC 6455 implementation over TCP sockets. This provides compression support and full protocol control.

Compression on Node.js does not support context takeover (each message is compressed independently).

## Browser

In the browser, the library uses `BrowserWebSocketController` which delegates to the browser's native `WebSocket` API. This means:

- The browser handles TLS, handshake, framing, and compression
- `permessage-deflate` support depends on the browser
- Ping/pong is not exposed (browser handles it internally; `isPingSupported()` returns `false`)
- `localPort()` and `remotePort()` are not available

```kotlin
// Same API works in both Node.js and Browser
val client = WebSocketClient.allocate(options)
client.connect()
```

The factory automatically detects the runtime environment and returns the appropriate implementation.

## Browser Limitations

| Feature | Node.js | Browser |
|---------|---------|---------|
| Compression control | Yes | No (browser-managed) |
| Ping/Pong | Yes | No |
| Port information | Yes | No |
| Custom headers | Yes | No |
| Binary frame access | Yes | Yes (ArrayBuffer) |
| TLS certificate control | Yes | No (browser-managed) |
