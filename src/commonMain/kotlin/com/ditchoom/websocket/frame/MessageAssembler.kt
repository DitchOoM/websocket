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
 * ## Usage
 *
 * ```kotlin
 * val assembler = MessageAssembler(compressionEnabled = false)
 *
 * while (true) {
 *     val frame = frameReader.readFrame() ?: continue
 *     when (val result = assembler.addFrame(frame)) {
 *         is AssemblyResult.ControlFrame -> handleControl(result.frame)
 *         is AssemblyResult.CompleteMessage -> handleMessage(result.message)
 *         is AssemblyResult.NeedMoreFrames -> continue
 *         is AssemblyResult.Error -> handleError(result.reason)
 *     }
 * }
 * ```
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
     * @param frame The parsed frame to add
     * @return The result of adding this frame
     */
    fun addFrame(frame: ParsedFrame): AssemblyResult {
        // RFC 6455: Reserved opcodes are not allowed
        if (frame is ParsedFrame.InvalidFrame) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                frame.reason,
            )
        }

        // RFC 6455 Section 5.2: RSV bits MUST be 0 unless an extension is negotiated.
        // RSV2 and RSV3 are never used by any defined extension.
        if (frame.rsv2) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                "RSV2 must be 0 when no extension defining its meaning is negotiated",
            )
        }
        if (frame.rsv3) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                "RSV3 must be 0 when no extension defining its meaning is negotiated",
            )
        }
        // RSV1 is used by permessage-deflate extension
        if (frame.rsv1 && !compressionEnabled) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                "RSV1 must be 0 when permessage-deflate is not negotiated",
            )
        }

        // Control frames are handled immediately and don't affect fragmentation state
        if (frame.isControlFrame) {
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

    private fun handleControlFrame(frame: ParsedFrame): AssemblyResult {
        // RFC 6455 Section 5.5: Control frames MUST have FIN set
        if (!frame.fin) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                "Control frame must have FIN set",
            )
        }

        // RFC 6455 Section 5.5: Control frames MUST have payload <= 125 bytes
        if (frame.payloadLength > 125) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                "Control frame payload exceeds 125 bytes",
            )
        }

        // RFC 6455 Section 7.4.1: Validate close frame
        if (frame is ParsedFrame.ControlFrame.Close) {
            // Check for invalid UTF-8 in close reason
            if (frame.hasInvalidUtf8) {
                return AssemblyResult.Error(
                    CloseCode.INVALID_PAYLOAD,
                    "Invalid UTF-8 in close reason",
                )
            }

            // Check if the close code is valid per RFC 6455 Section 7.4.1
            // Valid codes for wire transmission: 1000-1003, 1007-1011, 3000-3999, 4000-4999
            // Codes 1005, 1006, 1015 MUST NOT be sent on the wire
            // Invalid codes include: 0-999, 1004-1006, 1012-2999, 5000+
            if (frame.payloadLength >= 2 && !frame.closeCode.isValidForWire) {
                return AssemblyResult.Error(
                    CloseCode.PROTOCOL_ERROR,
                    "Invalid close code: ${frame.closeCode.code}",
                )
            }
        }

        return AssemblyResult.ControlFrame(frame)
    }

    private fun handleDataFrame(frame: ParsedFrame): AssemblyResult =
        when (frame) {
            is ParsedFrame.DataFrame.Text -> handleFirstFragment(frame)
            is ParsedFrame.DataFrame.Binary -> handleFirstFragment(frame)
            is ParsedFrame.DataFrame.Continuation -> handleContinuation(frame)
            is ParsedFrame.ControlFrame -> {
                // Should not reach here - control frames handled separately
                AssemblyResult.Error(
                    CloseCode.PROTOCOL_ERROR,
                    "Unexpected control frame in data frame handler",
                )
            }
            is ParsedFrame.InvalidFrame -> {
                // Should not reach here - invalid frames handled at the start of addFrame()
                AssemblyResult.Error(
                    CloseCode.PROTOCOL_ERROR,
                    frame.reason,
                )
            }
        }

    private fun handleFirstFragment(frame: ParsedFrame): AssemblyResult {
        // RFC 6455 Section 5.4: Cannot start new message while one is in progress
        if (isFragmentInProgress) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                "Received new message while fragment in progress",
            )
        }

        if (frame.fin) {
            // Complete message in a single frame (common case)
            // Note: FrameReader guarantees payload.position() == 0
            return AssemblyResult.CompleteMessage(
                AssembledMessage(
                    opcode = frame.opcode,
                    payload = frame.payload,
                    compressed = frame.rsv1,
                ),
            )
        }

        // Start of fragmented message
        firstFrameOpcode = frame.opcode
        firstFrameRsv1 = frame.rsv1
        fragmentBuffers.add(frame.payload)
        // Use payloadLength instead of remaining() to handle buffers with non-zero position
        totalPayloadSize += frame.payloadLength

        return AssemblyResult.NeedMoreFrames
    }

    private fun handleContinuation(frame: ParsedFrame): AssemblyResult {
        // RFC 6455 Section 5.4: Continuation without a starting fragment is an error
        if (!isFragmentInProgress) {
            return AssemblyResult.Error(
                CloseCode.PROTOCOL_ERROR,
                "Received continuation frame without starting fragment",
            )
        }

        fragmentBuffers.add(frame.payload)
        // Use payloadLength instead of remaining() to handle buffers with non-zero position
        totalPayloadSize += frame.payloadLength

        if (!frame.fin) {
            return AssemblyResult.NeedMoreFrames
        }

        // Final fragment - assemble the message.
        // Save fragment buffers before reset clears the list. For multi-fragment messages,
        // combineBuffers() copies data to a new Heap buffer, so the original fragment
        // NativeBuffers must be freed by the caller (Linux has no GC for native heap).
        // For single-fragment messages, the payload IS the fragment (no copy), so no cleanup needed.
        val fragments = if (fragmentBuffers.size > 1) fragmentBuffers.toList() else emptyList()
        val message = assembleMessage()
        // Clear before reset() to avoid double-free — fragments are returned
        // to the caller via fragmentsToClose for explicit cleanup.
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
            // Do NOT use position(0) — the buffer may be a view/slice where
            // position > 0 is the correct payload start.
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
        val frame: ParsedFrame,
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
    /**
     * Whether a close code is actually present in the close frame.
     * Per RFC 6455, status code 1005 (NO_STATUS_RECEIVED) indicates no code was present.
     */
    val isPresent: Boolean get() = code != 1005u.toUShort()

    /**
     * Whether this is a valid close code per RFC 6455 Section 7.4.1.
     * Valid codes are: 1000-1003, 1007-1011, 3000-3999, 4000-4999.
     */
    val isValid: Boolean
        get() =
            code in 1000u..1003u ||
                code in 1007u..1011u ||
                code in 3000u..3999u ||
                code in 4000u..4999u

    /**
     * Whether this close code is valid to receive from a peer (can be sent on the wire).
     * Per RFC 6455 Section 7.4.1, codes 1005, 1006, and 1015 MUST NOT be sent on the wire.
     * They are only used internally to indicate connection state.
     */
    val isValidForWire: Boolean
        get() {
            // Per RFC 6455 Section 7.4.1:
            // 1005 (NO_STATUS_RECEIVED), 1006 (ABNORMAL_CLOSURE), and 1015 (TLS_HANDSHAKE)
            // are reserved and MUST NOT be sent on the wire
            return isValid &&
                code != 1005u.toUShort() &&
                code != 1006u.toUShort() &&
                code != 1015u.toUShort()
        }

    companion object {
        /** 1000 - Normal closure */
        val NORMAL = CloseCode(1000u)

        /** 1002 - Protocol error */
        val PROTOCOL_ERROR = CloseCode(1002u)

        /**
         * 1005 - No status received. Per RFC 6455, this status code is used internally
         * and MUST NOT be sent on the wire. Indicates no close code was present in the frame.
         */
        val NO_STATUS_RECEIVED = CloseCode(1005u)

        /** 1007 - Invalid payload data (e.g., non-UTF-8 in text message) */
        val INVALID_PAYLOAD = CloseCode(1007u)
    }
}
