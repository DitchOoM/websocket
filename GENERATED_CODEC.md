Replace hand-written FrameReader/FrameWriter with generated sealed dispatch codec.

The KSP processor already supports everything needed — no processor changes required.

## Current state

WsFrameHeader and WsCloseBody already use @ProtocolMessage — generated at compile time.
But FrameReader (276 lines), FrameWriter (272 lines), MessageAssembler (380 lines),
and WebSocketCodec (260 lines) are hand-written. ~1200 lines of manual frame parsing,
serialization, read loops, and assembly.

## Approach

Model WsFrame as a sealed interface dispatched on FrameHeaderByte1 (opcode):

```kotlin
@ProtocolMessage
@DispatchOn(FrameHeaderByte1::class)
sealed interface WsFrame {
    val header: WsFrameHeader

    @PacketType(0x1)
    data class Text(override val header: WsFrameHeader, @UseCodec(WsPayloadCodec::class) val payload: ReadBuffer) : WsFrame

    @PacketType(0x2)
    data class Binary(override val header: WsFrameHeader, @UseCodec(WsPayloadCodec::class) val payload: ReadBuffer) : WsFrame

    @PacketType(0x0)
    data class Continuation(override val header: WsFrameHeader, @UseCodec(WsPayloadCodec::class) val payload: ReadBuffer) : WsFrame

    @PacketType(0x8)
    data class Close(override val header: WsFrameHeader, val body: WsCloseBody?) : WsFrame

    @PacketType(0x9)
    data class Ping(override val header: WsFrameHeader, @UseCodec(WsPayloadCodec::class) val payload: ReadBuffer) : WsFrame

    @PacketType(0xA)
    data class Pong(override val header: WsFrameHeader, @UseCodec(WsPayloadCodec::class) val payload: ReadBuffer) : WsFrame
}
```

WsPayloadCodec is a custom Codec<ReadBuffer> that:
- decode: reads payload length bytes (from header in discriminator context), XOR unmasks if masked
- encode: XOR masks payload if client mode, writes bytes

The SealedDispatchGenerator already:
1. Peeks byte 1, extracts opcode via FrameHeaderByte1 value class
2. Puts discriminator in context via context.with(DiscriminatorKey, _discriminator)
3. Dispatches to variant codec
4. Variant receives context — WsPayloadCodec reads header from context for length + mask key

Fragment assembly becomes a Connection.map() layer:
- Collects Continuation frames until FIN=1
- Emits assembled WebSocketMessage.Text/Binary
- Passes through Ping/Pong/Close as control messages
- Auto-pong on ping

connectWebSocket becomes:
1. Handshake (unchanged)
2. Read loop using generated WsFrameCodec.peekFrameSize() + decode() via StreamProcessor
3. Fragment assembly via Connection.map()
4. Returns Connection<WebSocketMessage>

## Files to delete after

- FrameReader.kt (~276 lines) — replaced by generated WsFrameCodec.decode()
- FrameWriter.kt (~272 lines) — replaced by generated WsFrameCodec.encode()
- WebSocketCodec.kt (~260 lines) — replaced by codec read loop + assembly map
- MessageAssembler.kt (~380 lines) — logic moves into assembly map (or stays as helper)

## What stays

- WsCodec.kt — expanded with WsFrame sealed interface + WsPayloadCodec
- handshake/ — unchanged
- CompressionConfig — unchanged
- Compression.kt helpers — used by assembly layer for decompression
- All tests — unchanged (test Connection<WebSocketMessage> surface, not internals)

## Verification

All existing mock Autobahn tests pass unchanged — they test the Connection<WebSocketMessage>
behavior, not the internal codec implementation.
