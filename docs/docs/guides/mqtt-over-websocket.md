---
id: mqtt-over-websocket
title: MQTT over WebSocket
sidebar_position: 1
---

# MQTT over WebSocket

The WebSocket library powers the transport layer for [`com.ditchoom:mqtt-client`](https://github.com/DitchOoM/mqtt), enabling MQTT communication over WebSocket connections.

## Basic Setup

```kotlin
val wsOptions = WebSocketConnectionOptions(
    name = "broker.example.com",
    port = 80,
    websocketEndpoint = "/mqtt",
    protocols = listOf("mqtt"),  // MQTT subprotocol
)

val client = WebSocketClient.allocate(wsOptions)
client.connect()

// Binary frames carry MQTT packets
client.incomingBinaryMessages.collect { buffer ->
    val mqttPacket = decodeMqttPacket(buffer)
    handlePacket(mqttPacket)
}
```

## With TLS

```kotlin
val wsOptions = WebSocketConnectionOptions(
    name = "broker.example.com",
    port = 443,
    tls = true,
    websocketEndpoint = "/mqtt",
    protocols = listOf("mqtt"),
)
```

## Subprotocol Negotiation

MQTT brokers typically require the `mqtt` or `mqttv3.1` subprotocol to be specified in the WebSocket handshake. The `protocols` parameter adds the `Sec-WebSocket-Protocol` header:

```kotlin
// MQTT 3.1.1
protocols = listOf("mqttv3.1")

// MQTT 5.0
protocols = listOf("mqtt")

// Accept either
protocols = listOf("mqtt", "mqttv3.1")
```
