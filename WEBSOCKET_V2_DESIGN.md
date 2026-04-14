# WebSocket v2 — Zero-Copy by Default, No User-Visible Buffers

## Goal

Redesign the public API to make zero-copy the default, eliminate user-visible `ReadBuffer` and `ByteArray`, and force users through `@ProtocolMessage`/`Codec` for payload types. Breaking change — no backward-compatibility shims.

Motivation surfaced by the v1 stress tests and the allocator investigation:

- The current API returns `WebSocketMessage.Binary(value: ReadBuffer)`, making users responsible for calling `freeIfNeeded()`. Getting that wrong = leaks.
- The library has to defensively copy payload bytes from the frame buffer into a fresh buffer so that the message payload outlives the pooled frame buffer. 1 MB messages → 1 MB memcpy per frame → measurable overhead.
- The `bufferFactory` parameter is a performance knob that most callers shouldn't need to touch. Worse, it interacts badly with the socket library's separate `ConnectionOptions.bufferFactory` default (see `socket/CONNECTION_OPTIONS_BUFFER_FACTORY_BYPASS.md`) and causes silent dual-allocator bugs.
- MQTT (`../mqtt`) already uses the cleaner pattern: `IPublishMessage<out P>` generic over a user-defined payload type with KSP-generated codecs. WebSocket should follow suit.

## Non-goals

- Backward compatibility with the v1 API. Users migrate.
- Supporting raw `ByteArray` or `ReadBuffer` as a payload type. If someone really needs opaque bytes, they define a `@ProtocolMessage data class` that wraps `String`/`@RemainingBytes` and pay a small ergonomic cost — deliberate, to keep the contract tight.
- Changing `buffer` or `buffer-codec` repos. v2 reuses the existing `Codec<P>`/`@ProtocolMessage`/`@Payload` machinery unchanged.

## Public API shape

```kotlin
// ================ Messages ================

sealed interface WebSocketMessage<out P> {
    /** Data message carrying a user payload of type P. Decoded via the connection's Codec<P>. */
    class Text<out P>(val payload: P) : WebSocketMessage<P>

    /** Data message carrying a user payload of type P. Decoded via the connection's Codec<P>. */
    class Binary<out P>(val payload: P) : WebSocketMessage<P>

    // Control frames have fixed structure per RFC 6455. No user codec.
    class Ping(val appData: String = "") : WebSocketMessage<Nothing>
    class Pong(val appData: String = "") : WebSocketMessage<Nothing>
    class Close(val code: UShort, val reason: String = "") : WebSocketMessage<Nothing>
}

// ================ Connection factory ================

/**
 * Establishes a WebSocket connection over [transport] that will decode and encode
 * payloads of type [P] using [payloadCodec].
 *
 * The codec is invoked once per received data frame with a zero-copy slice of the frame
 * buffer. After the codec returns, the frame buffer is released back to the pool — so
 * the returned `P` must not retain a reference to the slice. For POJO-shaped payloads
 * decoded from `@ProtocolMessage` fields, this is automatic; the generated codec reads
 * fields by value.
 */
suspend fun <P> connectWebSocket(
    transport: ByteStream,
    connectionOptions: WebSocketConnectionOptions,
    payloadCodec: Codec<P>,
): Connection<WebSocketMessage<P>>

// ================ Built-in codecs ================

/** UTF-8 text payload. Zero-copy decode via StreamingStringDecoder. */
object StringCodec : Codec<String>

/** No payload. Use when the app only cares about control frames. */
object EmptyCodec : Codec<Unit>

// No ByteArrayCodec. No ReadBufferCodec. Deliberate.
```

### Simple case — text chat

```kotlin
val ws: Connection<WebSocketMessage<String>> = connectWebSocket(
    transport = tcp,
    connectionOptions = opts,
    payloadCodec = StringCodec,
)

ws.receive().collect { msg ->
    when (msg) {
        is WebSocketMessage.Text -> display(msg.payload)
        is WebSocketMessage.Close -> reconnect(msg.code)
        else -> {}
    }
}

ws.send(WebSocketMessage.Text("hello"))
```

No buffer types in user code. No `freeIfNeeded`. No `bufferFactory`. One codec, three messages types to handle.

### Structured protocol case

```kotlin
@ProtocolMessage
data class SensorReading(
    val sensorId: UShort,
    @WireBytes(4) val temperatureMilliC: Int,
    @LengthPrefixed val location: String,
)
// KSP generates SensorReadingCodec at compile time.

val ws: Connection<WebSocketMessage<SensorReading>> = connectWebSocket(
    transport = tcp,
    connectionOptions = opts,
    payloadCodec = SensorReadingCodec,
)

ws.receive().collect { msg ->
    when (msg) {
        is WebSocketMessage.Binary -> ingest(msg.payload)    // msg.payload: SensorReading
        else -> {}
    }
}

ws.send(WebSocketMessage.Binary(SensorReading(1u, 22500, "rack-A-3")))
```

`msg.payload` is a `SensorReading` directly — no parsing boilerplate at the call site. The generated codec reads fields straight from the frame-buffer slice.

## Internal design

### Receive path (zero-copy)

```kotlin
// WebSocketCodec.readFrame (conceptual)
suspend fun readFrame(): WebSocketMessage<P>? {
    val buffer = stream.readBuffer(totalFrameSize)
    return try {
        val header = WsFrameHeader.decode(buffer)
        when (header.opcode) {
            Opcode.Text -> {
                val payload = payloadCodec.decode(PayloadReader(buffer, payloadStart, payloadLen))
                WebSocketMessage.Text(payload)
            }
            Opcode.Binary -> {
                val payload = payloadCodec.decode(PayloadReader(buffer, payloadStart, payloadLen))
                WebSocketMessage.Binary(payload)
            }
            Opcode.Ping -> WebSocketMessage.Ping(decodeUtf8(buffer, payloadStart, payloadLen))
            Opcode.Pong -> WebSocketMessage.Pong(decodeUtf8(buffer, payloadStart, payloadLen))
            Opcode.Close -> {
                val code = buffer.readUShort()
                val reason = if (payloadLen > 2) decodeUtf8(buffer, ...) else ""
                WebSocketMessage.Close(code, reason)
            }
            else -> throw ProtocolError
        }
    } finally {
        buffer.freeIfNeeded()
    }
}
```

**No `copyToBuffer` anywhere.** The codec decodes by value from a zero-copy slice. After `decode` returns, we free the frame buffer — safe because `P` holds no slice reference.

For the `StringCodec` and control-frame UTF-8 decoding, we use the existing `StreamingStringDecoder` on the slice, which writes into a `StringBuilder` — no intermediate buffer.

### Send path (zero-copy)

```kotlin
// WebSocketCodec.send (conceptual)
suspend fun send(message: WebSocketMessage<P>) {
    when (message) {
        is WebSocketMessage.Text<*> -> {
            val payloadSize = payloadCodec.encodedSize(message.payload as P)
            val frame = bufferFactory.allocate(headerSize + payloadSize)
            encodeHeader(frame, Opcode.Text, payloadSize, masked = true)
            payloadCodec.encode(message.payload as P, frame)   // writes directly into frame
            frame.xorMask(mask)                                  // in-place, payload region
            transport.write(frame)
            frame.freeIfNeeded()
        }
        // Binary/Ping/Pong/Close analogous.
    }
}
```

One allocation per outgoing frame. Codec writes payload straight into the frame buffer at the correct offset. Mask applied in place. One write to the socket. One free.

### Lifetime & ownership contract

- The library owns every buffer that ever exists. Users see `P`, `String`, `UShort`, numbers — never a buffer.
- `Codec<P>.decode(PayloadReader)` must return a fully-materialized `P`. If `P` held a reference to the underlying bytes (e.g., by slicing), it would be freed-behind-your-back. KSP-generated codecs read by value, so they're safe.
- `Codec<P>.encode(P, WriteBuffer)` writes fields into the caller-provided buffer. Library owns the buffer.

This eliminates the entire category of "who frees what" bugs that v1 has.

### Where does `bufferFactory` live?

Not in the public API. The library picks `BufferFactory.deterministic()` internally (matches pool lifecycle, zero-copy-compatible, synchronously releases native memory). If a caller has a principled reason to override — benchmarking, shared pool across many connections — we expose it as an internal/experimental knob on `WebSocketConnectionOptions`:

```kotlin
data class WebSocketConnectionOptions(
    val name: String,
    val port: Int,
    // ... existing fields ...
    @ExperimentalWebSocketApi
    val bufferFactory: BufferFactory = BufferFactory.deterministic(),
)
```

Most callers never touch it. If they do, the annotation makes it explicit that they're stepping outside the paved path.

## Migration notes

Breaking changes users need to make:

| v1 | v2 |
|---|---|
| `connectWebSocket(transport, opts, bufferFactory)` | `connectWebSocket(transport, opts, payloadCodec)` |
| `when (msg) { is WebSocketMessage.Text -> msg.value (String) }` | same pattern but `msg.payload` and type is `P` (may be `String` via `StringCodec`) |
| `when (msg) { is WebSocketMessage.Binary -> msg.value (ReadBuffer) }` | `msg.payload` is `P` (user's codec-defined type) |
| `msg.value.freeIfNeeded()` for binary | gone — library owns the buffer |
| `WebSocketMessage.Text(value = "hi")` | `WebSocketMessage.Text(payload = "hi")` |
| `WebSocketMessage.Binary(value = readBuffer)` | `WebSocketMessage.Binary(payload = myType)` |

For users who want "just send me bytes," the workaround is:

```kotlin
@ProtocolMessage
data class Opaque(@RemainingBytes val text: String)  // "bytes" are just utf8-shaped
object OpaqueCodec : Codec<Opaque> by OpaqueGeneratedCodec

val ws = connectWebSocket(transport, opts, OpaqueCodec)
```

It's slightly more friction than `ReadBuffer` for the bytes-passthrough case, but it's a deliberate speed-bump: you're declaring intent.

## Scope of implementation

Entirely within this (`websocket`) repo. No changes needed in `buffer`, `buffer-codec`, or `buffer-compression`. The MQTT repo is a working reference for the pattern at scale.

Rough shape of the commits:

1. Replace `WebSocketMessage` with generic version + add built-in codecs (`StringCodec`, `EmptyCodec`).
2. Make `connectWebSocket` generic over `P`, take a `Codec<P>`.
3. Rewrite `WebSocketCodec.readFrame` to invoke the user's codec on a zero-copy slice, drop `copyToBuffer`.
4. Rewrite the send path to invoke `codec.encode` directly into a single allocated frame buffer.
5. Remove `bufferFactory` from public `connectWebSocket`; move behind `@ExperimentalWebSocketApi` in `WebSocketConnectionOptions`.
6. Port every test in `src/commonTest`:
   - `Mock*` tests: switch to `StringCodec` or define per-test codec types.
   - Autobahn tests: likely `StringCodec` (text payloads are just UTF-8 bytes).
   - `AutobahnStressTests`: drop the factory parameterization; keep the "sustained throughput" shape.
   - `AllocatorLeakTests`: keep as-is but update to pass a `Codec` instead of `BufferFactory` (still uses a user-passed `BufferPool` for stats, just inside the opts struct).

## What this does NOT fix

- The `socket` repo's `ConnectionOptions.bufferFactory` default (`BufferFactory.Default`). That's a separate bug with its own doc at `socket/CONNECTION_OPTIONS_BUFFER_FACTORY_BYPASS.md`. v2's internal `deterministic()` default works around it but doesn't eliminate it for other socket users.
- The `BufferPool`'s LIFO-without-size-classes behavior under mixed payload sizes. Worth a follow-up in the `buffer` repo.
- The double-pooling behavior when a user passes a `BufferPool` as the factory (gets wrapped in another `BufferPool` by `ConnectionContext`). Also a `buffer`/`socket` repo concern.

## References

- `../mqtt/models-base/src/commonMain/kotlin/com/ditchoom/mqtt/controlpacket/IPublishMessage.kt` — the generic-payload pattern we're mirroring.
- `../buffer/buffer-codec/src/commonMain/kotlin/com/ditchoom/buffer/codec/annotations/Annotations.kt` — `@ProtocolMessage`, `@Payload`, KSP annotations we reuse.
- `src/commonTest/kotlin/com/ditchoom/websocket/AllocatorLeakTests.kt` — regression harness that will port cleanly to v2.
- `src/commonTest/kotlin/com/ditchoom/websocket/AutobahnStressTests.kt` — factory-parameterized stress; v2 retires the parameterization and keeps the shape.
