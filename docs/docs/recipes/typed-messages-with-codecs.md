---
id: typed-messages-with-codecs
title: Typed Messages with Codecs
sidebar_position: 1
---

# Typed Messages with Codecs

Most WebSocket libraries hand you a `ByteArray` or `ByteBuffer` for binary frames and leave parsing
to you. Here, **binary frames run a `Codec<B>` you supply**, so `receive()` emits your own decoded
type and `send()` takes it — the generic threads all the way through as
`Connection<WebSocketMessage<B>>`.

Text frames are always `String` (RFC 6455 §5.6 mandates UTF-8); only **binary** frames run the codec.

## Supplying a codec

Every `connect*` function takes an optional `binaryCodec`:

```kotlin
val ws: Connection<WebSocketMessage<SensorReading>> =
    connectTcpWebSocket(options, binaryCodec = SensorReadingCodec)

ws.send(WebSocketMessage.Binary(SensorReading(id = 7u, celsius = 21)))

ws.receive().collect { msg ->
    when (msg) {
        is WebSocketMessage.Binary -> update(msg.payload)   // payload: SensorReading, already decoded
        is WebSocketMessage.Text   -> log(msg.payload)      // still a String
        else -> {}
    }
}
```

Omit `binaryCodec` and you get the default `EmptyCodec` — binary payloads are discarded and `B` is
`Unit`.

## Built-in codecs

`com.ditchoom.websocket.codecs`:

| Codec | `B` | Behavior |
|-------|-----|----------|
| `EmptyCodec` | `Unit` | Default — discards binary payloads (text-only apps) |
| `BinaryPassThroughCodec` | `ReadBuffer` | Raw bytes, copied into a consumer-owned buffer you can read/free |
| `RejectingCodec` | `Nothing` | Throws on any binary frame — assert "this endpoint is text-only" and fail loudly |

```kotlin
// Raw bytes:
connectTcpWebSocket(options, binaryCodec = BinaryPassThroughCodec).use { ws ->
    ws.receive().collect { msg ->
        if (msg is WebSocketMessage.Binary) processBytes(msg.payload)   // payload: ReadBuffer
    }
}
```

## Generated codecs (buffer-codec)

The cleanest way to get a `Codec<B>` is to let [`buffer-codec`](https://github.com/DitchOoM/buffer)
generate one from an annotated data class — no hand-written field wiring:

```kotlin
@ProtocolMessage
data class SensorReading(
    val id: UShort,                 // 2 bytes
    val celsius: Int,               // 4 bytes
    @LengthPrefixed val label: String,
)
// The KSP processor generates `object SensorReadingCodec : Codec<SensorReading>`.

connectTcpWebSocket(options, binaryCodec = SensorReadingCodec)
```

Because generated and hand-written codecs implement the same `Codec<T>`, they drop into
`binaryCodec` interchangeably.

## Writing a custom codec

A `Codec<T>` is two methods over `ReadBuffer`/`WriteBuffer`:

```kotlin
object FrameCountCodec : Codec<Int> {
    override fun decode(buffer: ReadBuffer, context: DecodeContext): Int = buffer.readInt()

    override fun encode(buffer: WriteBuffer, value: Int, context: EncodeContext) {
        buffer.writeInt(value)
    }
}

connectTcpWebSocket(options, binaryCodec = FrameCountCodec)
```

Notes that make this safe and allocation-friendly:

- **Decode runs after fragment reassembly**, on the fully-defragmented payload — you never handle
  continuation frames.
- The wire buffer is **freed before your decoded value is emitted**, so what `receive()` gives you
  never aliases a library buffer.
- Need to allocate a destination buffer in `decode`? Pull the connection's own `BufferFactory` out
  of the `DecodeContext` under `WsBufferFactoryKey`, so your codec allocates from the same pool as
  the wire path (this is exactly how `BinaryPassThroughCodec` gets its buffers):

  ```kotlin
  val factory = context[WsBufferFactoryKey] ?: BufferFactory.Default
  val out = factory.allocate(size)
  ```
