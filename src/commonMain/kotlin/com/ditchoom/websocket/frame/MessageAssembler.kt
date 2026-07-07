package com.ditchoom.websocket.frame

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.managed
import com.ditchoom.websocket.Opcode
import com.ditchoom.websocket.internal.GrowableWriteBuffer
import kotlin.jvm.JvmInline

/**
 * Assembles fragmented WebSocket messages from individual frames.
 *
 * Per RFC 6455 Section 5.4, data messages may be split across multiple frames:
 * - First fragment: opcode = Text/Binary, FIN = 0
 * - Middle fragments: opcode = Continuation, FIN = 0
 * - Final fragment: opcode = Continuation, FIN = 1
 *
 * Control frames (Close, Ping, Pong) may be interspersed between data fragments
 * but cannot themselves be fragmented.
 *
 * ## RFC 6455 Section 5.4 - Fragmentation
 * https://datatracker.ietf.org/doc/html/rfc6455#section-5.4
 *
 * @param compressionEnabled Whether permessage-deflate compression is negotiated.
 *                           When true, RSV1 bit is allowed on data frames.
 */
internal class MessageAssembler(
    private val compressionEnabled: Boolean = false,
    private val bufferFactory: BufferFactory = BufferFactory.managed(),
) {
    private var firstFrameOpcode: Opcode? = null
    private var firstFrameRsv1: Boolean = false

    /**
     * Assembler-owned accumulation buffer for the in-progress fragmented message.
     *
     * Each data fragment's payload is **copied into this buffer the moment the frame
     * arrives** (see [appendFragment]); the source wire buffer is then released
     * immediately. This is a deliberate departure from aliasing the wire buffers and
     * copying lazily at the end: buffers handed out by the frame stream are only slices
     * over pool/native chunk memory, and a *later* read can free the parent chunk (e.g.
     * `DefaultStreamProcessor.readBuffer` drains and frees a chunk on the multi-chunk
     * copy path) while we still hold an earlier fragment's slice. On `deterministic()`
     * (raw-malloc `NativeBuffer`, whose slices share the parent's pointer and whose
     * `write` is an unchecked memcpy) that stale read segfaults; `Default` masked it only
     * because its `freeNativeMemory` is a no-op. Copying eagerly makes the assembler's
     * data independent of any wire-buffer lifetime.
     */
    private var accumulator: GrowableWriteBuffer? = null
    private var totalPayloadSize = 0

    /**
     * Adds a frame to the assembler.
     *
     * @param frame The decoded frame to add
     * @return The result of adding this frame
     */
    fun addFrame(frame: WsFrame<BufferPayload>): AssemblyResult {
        val h = frame.byte1

        // RFC 6455 Section 5.2: RSV bits MUST be 0 unless an extension is negotiated.
        // RSV2 and RSV3 are never used by any defined extension.
        if (h.rsv2) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                "RSV2 must be 0 when no extension defining its meaning is negotiated",
            )
        }
        if (h.rsv3) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                "RSV3 must be 0 when no extension defining its meaning is negotiated",
            )
        }
        // RSV1 is used by permessage-deflate extension
        if (h.rsv1 && !compressionEnabled) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                "RSV1 must be 0 when permessage-deflate is not negotiated",
            )
        }

        // Control frames are handled immediately and don't affect fragmentation state
        if (frame is WsFrame.Close || frame is WsFrame.Ping<*> || frame is WsFrame.Pong<*>) {
            return handleControlFrame(frame)
        }

        return handleDataFrame(frame)
    }

    /**
     * Resets the assembler state, discarding any partial message.
     *
     * Frees the accumulation buffer if one is still owned here (partial message discarded
     * on error / EOF). After a successful assembly, [finishAssembly] detaches the buffer
     * before calling this, so the payload handed to the caller is never freed.
     */
    fun reset() {
        accumulator?.underlying?.freeIfNeeded()
        accumulator = null
        firstFrameOpcode = null
        firstFrameRsv1 = false
        totalPayloadSize = 0
    }

    /**
     * Returns true if a fragmented message is in progress.
     */
    val isFragmentInProgress: Boolean
        get() = firstFrameOpcode != null

    private fun handleControlFrame(frame: WsFrame<BufferPayload>): AssemblyResult {
        val byte1 = frame.byte1
        val payloadLength = frame.payloadLength
        // RFC 6455 Section 5.5: Control frames MUST have FIN set
        if (!byte1.fin) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                "Control frame must have FIN set",
            )
        }

        // RFC 6455 Section 5.5: Control frames MUST have payload <= 125 bytes
        if (payloadLength > 125) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                "Control frame payload exceeds 125 bytes",
            )
        }

        // RFC 6455 Section 7.4.1: Validate close frame
        if (frame is WsFrame.Close) {
            // Close payload must be 0 or >= 2 bytes (status code is 2 bytes)
            if (payloadLength == 1L) {
                return AssemblyResult.Error(
                    CloseCode.PROTOCOL_ERROR,
                    "Invalid close payload length",
                )
            }
            val closeCode = frame.body?.statusCode ?: CloseCode.NO_STATUS_RECEIVED
            // Check if the close code is valid per RFC 6455 Section 7.4.1
            if (payloadLength >= 2 && !closeCode.isValidForWire) {
                return AssemblyResult.Error(
                    CloseCode.PROTOCOL_ERROR,
                    "Invalid close code: ${closeCode.code}",
                )
            }
        }

        return AssemblyResult.ControlFrame(frame)
    }

    private fun handleDataFrame(frame: WsFrame<BufferPayload>): AssemblyResult =
        when (frame) {
            is WsFrame.Text<BufferPayload> -> handleFirstFragment(frame, frame.payload.buffer)
            is WsFrame.Binary<BufferPayload> -> handleFirstFragment(frame, frame.payload.buffer)
            is WsFrame.Continuation<BufferPayload> -> handleContinuation(frame)
            is WsFrame.Close, is WsFrame.Ping<*>, is WsFrame.Pong<*> -> {
                // Should not reach here - control frames handled separately
                AssemblyResult.Error(
                    CloseCode.PROTOCOL_ERROR,
                    "Unexpected control frame in data frame handler",
                )
            }
        }

    private fun handleFirstFragment(
        frame: WsFrame<BufferPayload>,
        payload: ReadBuffer,
    ): AssemblyResult {
        val byte1 = frame.byte1
        val payloadLen = frame.payloadLength.toInt()

        // RFC 6455 Section 5.4: Cannot start new message while one is in progress
        if (isFragmentInProgress) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                "Received new message while fragment in progress",
            )
        }

        if (byte1.fin) {
            // Complete message in a single frame (common case). Zero-copy: the wire-buffer
            // view is returned directly and ownership transfers to the caller, which frees
            // it after running the user codec.
            return AssemblyResult.CompleteMessage(
                AssembledMessage(
                    opcode = byte1.opcode,
                    payload = payload,
                    compressed = byte1.rsv1,
                ),
            )
        }

        // Start of fragmented message: begin an assembler-owned accumulation buffer and
        // copy the first fragment in (freeing the wire buffer). Size it to the first
        // fragment so a large opening fragment allocates once; small fragments start at
        // the growable's floor and double as continuations arrive.
        firstFrameOpcode = byte1.opcode
        firstFrameRsv1 = byte1.rsv1
        accumulator = GrowableWriteBuffer(bufferFactory, initialSize = payloadLen.coerceAtLeast(INITIAL_ACCUMULATOR_SIZE))
        appendFragment(payload, payloadLen)

        return AssemblyResult.NeedMoreFrames
    }

    /**
     * Copies a data fragment's payload into the assembler-owned [accumulator] and releases
     * the source wire buffer immediately. The copy happens synchronously as the frame is
     * received — while its backing is still valid — so nothing the frame stream does on a
     * later read can invalidate the assembled bytes.
     */
    private fun appendFragment(
        payload: ReadBuffer,
        payloadLen: Int,
    ) {
        if (payloadLen > 0) accumulator!!.write(payload)
        payload.freeIfNeeded()
        totalPayloadSize += payloadLen
    }

    private fun handleContinuation(frame: WsFrame.Continuation<BufferPayload>): AssemblyResult {
        // RFC 6455 Section 5.4: Continuation without a starting fragment is an error
        if (!isFragmentInProgress) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                "Received continuation frame without starting fragment",
            )
        }

        appendFragment(frame.payload.buffer, frame.payloadLength.toInt())

        if (!frame.byte1.fin) {
            return AssemblyResult.NeedMoreFrames
        }

        // Final fragment — the accumulator already holds every fragment's bytes (copied at
        // arrival), so no source wire buffer is read here. The caller owns the returned payload.
        return AssemblyResult.CompleteMessage(finishAssembly())
    }

    /**
     * Finalizes the in-progress fragmented message. The [accumulator] already owns a
     * contiguous copy of every fragment's payload; this flips it to read mode and transfers
     * ownership to the caller (which frees it after running the user codec). For a
     * zero-length message the accumulator is freed and [EMPTY_BUFFER] is returned.
     *
     * The accumulator reference is detached before [reset] so the transferred buffer is
     * never freed by the reset.
     */
    private fun finishAssembly(): AssembledMessage {
        val opcode = firstFrameOpcode!!
        val rsv1 = firstFrameRsv1
        val acc = accumulator!!
        val payload: ReadBuffer =
            if (totalPayloadSize == 0) {
                acc.underlying.freeIfNeeded()
                EMPTY_BUFFER
            } else {
                acc.underlying.also { it.resetForRead() }
            }
        accumulator = null
        reset()
        return AssembledMessage(
            opcode = opcode,
            payload = payload,
            compressed = rsv1,
        )
    }

    private companion object {
        /** Floor for a fragmented message's accumulation buffer; grows by doubling. */
        const val INITIAL_ACCUMULATOR_SIZE = 256
    }
}

/**
 * Result of adding a frame to the assembler.
 */
internal sealed interface AssemblyResult {
    /**
     * A control frame was received. Handle it immediately.
     * Control frames can be interspersed between data frame fragments.
     */
    data class ControlFrame(
        val frame: WsFrame<BufferPayload>,
    ) : AssemblyResult

    /**
     * A complete message has been assembled. The assembler frees any source wire buffers
     * internally (multi-fragment case) before returning; for single-fragment messages,
     * `message.payload` IS the raw wire buffer view and ownership transfers to the caller,
     * which must free it after running the user codec.
     */
    data class CompleteMessage(
        val message: AssembledMessage,
    ) : AssemblyResult

    /**
     * More frames are needed to complete the message.
     */
    data object NeedMoreFrames : AssemblyResult

    /**
     * A protocol error occurred.
     */
    data class Error(
        val code: CloseCode,
        val reason: String,
    ) : AssemblyResult
}

/**
 * A fully assembled WebSocket message.
 *
 * @property opcode The message type (Text or Binary)
 * @property payload The complete message payload
 * @property compressed Whether the message was compressed (RSV1 bit set on first frame)
 */
internal data class AssembledMessage(
    val opcode: Opcode,
    val payload: ReadBuffer,
    val compressed: Boolean,
)

/**
 * WebSocket close status codes per RFC 6455 Section 7.4.1.
 * https://datatracker.ietf.org/doc/html/rfc6455#section-7.4.1
 *
 * This value class provides zero-allocation overhead for close code handling.
 */
@JvmInline
@com.ditchoom.buffer.codec.annotations.ProtocolMessage
value class CloseCode(
    val code: UShort,
) {
    val isPresent: Boolean get() = code != 1005u.toUShort()

    val isValid: Boolean
        get() =
            code in 1000u..1003u ||
                code in 1007u..1011u ||
                code in 3000u..3999u ||
                code in 4000u..4999u

    val isValidForWire: Boolean
        get() {
            return isValid &&
                code != 1005u.toUShort() &&
                code != 1006u.toUShort() &&
                code != 1015u.toUShort()
        }

    companion object {
        val NORMAL = CloseCode(1000u)
        val PROTOCOL_ERROR = CloseCode(1002u)
        val NO_STATUS_RECEIVED = CloseCode(1005u)
        val INVALID_PAYLOAD = CloseCode(1007u)
    }
}
