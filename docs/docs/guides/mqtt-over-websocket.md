---
id: mqtt-over-websocket
title: MQTT over WebSocket
sidebar_position: 1
---

# MQTT over WebSocket

MQTT brokers commonly accept connections over WebSocket: MQTT control packets ride **binary**
WebSocket frames, negotiated with the `mqtt` subprotocol. Two ways to wire it up.

## As a byte transport (recommended)

An MQTT client just needs a bidirectional byte stream. `WebSocketTransport` (from `websocket-tcp`)
*is* a `socket` `Transport`, so a socket-based MQTT client can run over WebSocket by swapping its
transport — no MQTT-specific WebSocket glue:

```kotlin
import com.ditchoom.websocket.tcp.WebSocketTransport

val transport = WebSocketTransport(
    websocketEndpoint = "/mqtt",
    protocols = listOf("mqtt"),         // Sec-WebSocket-Protocol
    underlying = TcpTransport(),         // TLS follows config.tls
)

// Hand `transport` to a socket-based client (e.g. com.ditchoom:mqtt-client).
// Each write() becomes one binary WebSocket frame; each read() yields the next payload.
```

This is how [`com.ditchoom:mqtt-client`](https://github.com/DitchOoM/mqtt) speaks MQTT over WebSocket:
the same packet codec, a different transport rung. See
[WebSocket as a transport](../recipes/websocket-as-transport.md).

## Directly, with a binary codec

If you want to handle frames yourself, connect with `BinaryPassThroughCodec` so binary frames arrive
as raw `ReadBuffer`s you can decode into MQTT packets:

```kotlin
import com.ditchoom.websocket.codecs.BinaryPassThroughCodec

val options = WebSocketConnectionOptions(
    name = "broker.example.com",
    port = 443,
    tls = true,
    websocketEndpoint = "/mqtt",
    protocols = listOf("mqtt"),
)

connectTcpWebSocket(options, binaryCodec = BinaryPassThroughCodec).use { ws ->
    ws.send(WebSocketMessage.Binary(encodeConnectPacket()))     // a ReadBuffer of MQTT bytes

    ws.receive().collect { message ->
        if (message is WebSocketMessage.Binary) {
            handlePacket(decodeMqttPacket(message.payload))     // payload: ReadBuffer
        }
    }
}
```

Better still, supply a real MQTT `Codec<MqttPacket>` as `binaryCodec` and let `receive()` hand you
decoded packets directly — see [Typed messages with codecs](../recipes/typed-messages-with-codecs.md).

## Subprotocol negotiation

The `protocols` list becomes the `Sec-WebSocket-Protocol` header. Most brokers require it:

```kotlin
protocols = listOf("mqtt")               // MQTT 5.0 / 3.1.1 over WS (RFC-registered name)
protocols = listOf("mqttv3.1")           // legacy brokers
protocols = listOf("mqtt", "mqttv3.1")   // offer both; server picks
```
