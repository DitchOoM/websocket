package com.ditchoom.websocket.frame

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.managed
import com.ditchoom.websocket.Opcode
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
class MessageAssembler(
    private val compressionEnabled: Boolean = false,
    private val bufferFactory: BufferFactory = BufferFactory.managed(),
) {
    private var firstFrameOpcode: Opcode? = null
    private var firstFrameRsv1: Boolean = false
    private val fragmentBuffers = mutableListOf<ReadBuffer>()
    private var totalPayloadSize = 0

    /**
     * Adds a frame to the assembler.
     *
     * @param frame The decoded frame to add
     * @return The result of adding this frame
     */
    fun addFrame(frame: WsFrame): AssemblyResult {
        val h = frame.header.byte1

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
     */
    fun reset() {
        fragmentBuffers.freeAll()
        firstFrameOpcode = null
        firstFrameRsv1 = false
        fragmentBuffers.clear()
        totalPayloadSize = 0
    }

    /**
     * Returns true if a fragmented message is in progress.
     */
    val isFragmentInProgress: Boolean
        get() = firstFrameOpcode != null

    private fun handleControlFrame(frame: WsFrame): AssemblyResult {
        val h = frame.header
        // RFC 6455 Section 5.5: Control frames MUST have FIN set
        if (!h.byte1.fin) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                "Control frame must have FIN set",
            )
        }

        // RFC 6455 Section 5.5: Control frames MUST have payload <= 125 bytes
        if (h.payloadLength > 125) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                "Control frame payload exceeds 125 bytes",
            )
        }

        // RFC 6455 Section 7.4.1: Validate close frame
        if (frame is WsFrame.Close) {
            val closeCode = frame.body?.statusCode ?: CloseCode.NO_STATUS_RECEIVED
            // Check if the close code is valid per RFC 6455 Section 7.4.1
            if (h.payloadLength >= 2 && !closeCode.isValidForWire) {
                return AssemblyResult.Error(
                    CloseCode.PROTOCOL_ERROR,
                    "Invalid close code: ${closeCode.code}",
                )
            }
        }

        return AssemblyResult.ControlFrame(frame)
    }

    private fun handleDataFrame(frame: WsFrame): AssemblyResult =
        when (frame) {
            is WsFrame.Text<*> -> handleFirstFragment(frame)
            is WsFrame.Binary<*> -> handleFirstFragment(frame)
            is WsFrame.Continuation<*> -> handleContinuation(frame)
            is WsFrame.Close, is WsFrame.Ping<*>, is WsFrame.Pong<*> -> {
                // Should not reach here - control frames handled separately
                AssemblyResult.Error(
                    CloseCode.PROTOCOL_ERROR,
                    "Unexpected control frame in data frame handler",
                )
            }
        }

    private fun handleFirstFragment(frame: WsFrame): AssemblyResult {
        val h = frame.header
        val payload = when (frame) {
            is WsFrame.Text<*> -> frame.payload as ReadBuffer
            is WsFrame.Binary<*> -> frame.payload as ReadBuffer
            else -> return AssemblyResult.Error(CloseCode.PROTOCOL_ERROR, "Unexpected frame type")
        }
        val payloadLen = h.payloadLength.toInt()

        // RFC 6455 Section 5.4: Cannot start new message while one is in progress
        if (isFragmentInProgress) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                "Received new message while fragment in progress",
            )
        }

        if (h.byte1.fin) {
            // Complete message in a single frame (common case)
            return AssemblyResult.CompleteMessage(
                AssembledMessage(
                    opcode = h.byte1.opcode,
                    payload = payload,
                    compressed = h.byte1.rsv1,
                ),
            )
        }

        // Start of fragmented message
        firstFrameOpcode = h.byte1.opcode
        firstFrameRsv1 = h.byte1.rsv1
        fragmentBuffers.add(payload)
        totalPayloadSize += payloadLen

        return AssemblyResult.NeedMoreFrames
    }

    private fun handleContinuation(frame: WsFrame.Continuation<*>): AssemblyResult {
        val h = frame.header
        // RFC 6455 Section 5.4: Continuation without a starting fragment is an error
        if (!isFragmentInProgress) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                "Received continuation frame without starting fragment",
            )
        }

        fragmentBuffers.add(frame.payload as ReadBuffer)
        totalPayloadSize += h.payloadLength.toInt()

        if (!h.byte1.fin) {
            return AssemblyResult.NeedMoreFrames
        }

        // Final fragment - assemble the message.
        val fragments = if (fragmentBuffers.size > 1) fragmentBuffers.toList() else emptyList()
        val message = assembleMessage()
        fragmentBuffers.clear()
        reset()
        return AssemblyResult.CompleteMessage(message, fragments)
    }

    private fun assembleMessage(): AssembledMessage {
        val payload =
            when {
                fragmentBuffers.size == 1 -> fragmentBuffers[0]
                totalPayloadSize == 0 -> EMPTY_BUFFER
                else -> combineBuffers()
            }

        return AssembledMessage(
            opcode = firstFrameOpcode!!,
            payload = payload,
            compressed = firstFrameRsv1,
        )
    }

    private fun combineBuffers(): ReadBuffer {
        val combined = bufferFactory.allocate(totalPayloadSize)
        for (buf in fragmentBuffers) {
            combined.write(buf)
        }
        combined.resetForRead()
        return combined
    }
}

/**
 * Result of adding a frame to the assembler.
 */
sealed interface AssemblyResult {
    /**
     * A control frame was received. Handle it immediately.
     * Control frames can be interspersed between data frame fragments.
     */
    data class ControlFrame(
        val frame: WsFrame,
    ) : AssemblyResult

    /**
     * A complete message has been assembled.
     *
     * @property fragmentsToClose Fragment payload buffers that should be closed after processing.
     *   For multi-fragment messages, combineBuffers() copies data to a new buffer, but the
     *   original fragment NativeBuffers must be explicitly freed on Linux (no GC for native heap).
     *   Empty for single-frame messages where the payload IS the fragment buffer.
     */
    data class CompleteMessage(
        val message: AssembledMessage,
        val fragmentsToClose: List<ReadBuffer> = emptyList(),
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
data class AssembledMessage(
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
