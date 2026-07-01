---
id: javascript
title: JavaScript
sidebar_position: 4
---

# JavaScript

## Node.js

On Node.js, use `connectTcpWebSocket` from `com.ditchoom:websocket-tcp` to open a
TCP socket and run the full RFC 6455 implementation (handshake, framing, masking,
permessage-deflate). Every feature exposed by the library's
`Connection<WebSocketMessage<B>>` API works on Node.

Alternatively, call `connectWebSocket(transport, options, codec)` from the core
module with a pre-connected `ByteStream` for full transport control.

Compression on Node.js does not support context takeover (each message is compressed
independently); window-bits control is also unavailable because the sync `zlib`
entry points create a fresh compressor per call.

## Browser

In the browser, `connectBrowserWebSocket` (in the core module's JS source set)
wraps `BrowserWebSocketController`, which delegates to the browser's native
`WebSocket` API. The browser owns TLS, the HTTP upgrade, frame encoding/masking,
and permessage-deflate negotiation — the library never touches those code paths
on this target.

```kotlin
// Browser — uses native WebSocket under the hood.
val ws = connectBrowserWebSocket(
    WebSocketConnectionOptions(name = "echo.websocket.org", port = 443, tls = true),
)
ws.send(WebSocketMessage.Text("hello"))
```

### What the browser `WebSocket` API does not expose

These are constraints of the platform, not the library. Consumer code running in a
browser cannot reach the following behaviour even if the server supports it:

| Capability | Why it's unreachable |
| --- | --- |
| App-level ping/pong frames | `WebSocket.send()` takes `string`/`Blob`/`ArrayBuffer` only; the browser handles protocol keep-alive itself. `WebSocketMessage.Ping`/`Pong` sends are silently dropped. |
| Non-standard close codes (1004, 1005, 1006, 1012–1014, anything outside 1000 or 3000–4999) | `WebSocket.close(code, reason)` rejects reserved/invalid codes. |
| `permessage-deflate` window-bits / context-takeover control | The browser negotiates deflate on your behalf; `compressionOptions` on the `WebSocketConnectionOptions` is ignored on this target. |
| Custom HTTP upgrade headers (except `Sec-WebSocket-Protocol`) | No spec-level browser API exists. |
| TLS certificate / cipher configuration | The browser's trust store and TLS stack are used. |
| Local/remote port introspection | Not exposed to page JavaScript. |

### Autobahn coverage

The autobahn suite runs against the browser target through `connectBrowserWebSocket`
— the fuzzingserver reports under the `BrowserJS` agent name and covers the
protocol-conformance categories reachable through the browser `WebSocket` API
(payload content, fragmentation, UTF-8 validation, most close semantics,
compression end-to-end).

The categories that depend on features the browser doesn't expose are excluded
from `jsBrowserTest` with individual comments in `build.gradle.kts`: autobahn case 7
invalid-close-code variants, case 13 window-bits behavior, `WebSocketTests.pingPongWorks`.
