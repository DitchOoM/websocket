---
id: apple
title: Apple (iOS, macOS, tvOS, watchOS)
sidebar_position: 2
---

# Apple Platforms

Apple targets have **two** ways to connect, and both return the same
`Connection<WebSocketMessage<B>>` — so the rest of your code is identical to every other platform.

## Option 1 — RFC 6455 engine over a socket (`websocket-tcp`)

`connectTcpWebSocket` runs this library's own RFC 6455 engine on top of a `socket` transport
(Network.framework `NWConnection`). This is the portable choice — the same call works on JVM,
Android, Linux, and Node.js — and it gives you full control over permessage-deflate.

```kotlin
import com.ditchoom.websocket.tcp.connectTcpWebSocket

connectTcpWebSocket(options).use { ws ->
    ws.send(WebSocketMessage.Text("hello"))
    val reply = ws.receive().first()
}
```

## Option 2 — system WebSocket (`websocket-apple`)

`connectAppleNativeWebSocket` is backed by `NSURLSessionWebSocketTask`, so the **system** handles TLS,
proxies, and permessage-deflate. It has the smallest binary (no `socket` dependency) and integrates
with URLSession configuration — ideal for an app already living in the Apple networking stack.

```kotlin
import com.ditchoom.websocket.apple.connectAppleNativeWebSocket

val ws = connectAppleNativeWebSocket(
    connectionOptions = options,
    // optional: handle TLS auth challenges (client certs, pinning)
    authChallengeHandler = { challenge -> resolveCredential(challenge) },
)
ws.use { /* send / receive as usual */ }
```

Both take an optional `binaryCodec: Codec<B>` and `parentScope: CoroutineScope?`, exactly like
`connectTcpWebSocket`.

## Which to choose

| | `connectTcpWebSocket` (`websocket-tcp`) | `connectAppleNativeWebSocket` (`websocket-apple`) |
|---|---|---|
| Backing | RFC 6455 engine over `NWConnection` | `NSURLSessionWebSocketTask` |
| TLS / proxy / deflate | this library | the OS |
| Binary size | larger (pulls in `socket`) | smallest |
| Portability | identical call on all platforms | Apple-only |
| Best for | shared multiplatform code, deflate control | Apple-native apps, URLSession integration |

## Compression

Apple supports `permessage-deflate` with context takeover, but not custom window bits. With
`websocket-apple` the OS owns compression entirely; with `websocket-tcp` this library negotiates and
runs it via zlib.

> There is also a lower-level `connectNativeWebSocket` primitive (raw `NWConnection` +
> `nw_protocol_websocket`) that returns a platform-native `NativeWebSocketConnection` rather than a
> `Connection<…>`. It underlies the Apple path and is not the recommended entry point — prefer the
> two `Connection`-returning functions above.
