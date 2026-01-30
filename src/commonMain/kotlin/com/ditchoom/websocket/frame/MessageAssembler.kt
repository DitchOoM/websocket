package com.ditchoom.websocket.frame

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.allocate
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
 * val assembler = MessageAssembler()
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
 */
class MessageAssembler {
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
        totalPayloadSize += frame.payload.remaining()

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
        totalPayloadSize += frame.payload.remaining()

        if (!frame.fin) {
            return AssemblyResult.NeedMoreFrames
        }

        // Final fragment - assemble the message
        val message = assembleMessage()
        reset()
        return AssemblyResult.CompleteMessage(message)
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
        val combined = PlatformBuffer.allocate(totalPayloadSize, AllocationZone.Heap)
        for (buf in fragmentBuffers) {
            buf.position(0)
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

    companion object {
        /** 1000 - Normal closure */
        val NORMAL = CloseCode(1000u)

        /** 1001 - Endpoint going away */
        val GOING_AWAY = CloseCode(1001u)

        /** 1002 - Protocol error */
        val PROTOCOL_ERROR = CloseCode(1002u)

        /** 1003 - Unsupported data type */
        val UNSUPPORTED_DATA = CloseCode(1003u)

        /**
         * 1005 - No status received. Per RFC 6455, this status code is used internally
         * and MUST NOT be sent on the wire. Indicates no close code was present in the frame.
         */
        val NO_STATUS_RECEIVED = CloseCode(1005u)

        /** 1007 - Invalid payload data (e.g., non-UTF-8 in text message) */
        val INVALID_PAYLOAD = CloseCode(1007u)

        /** 1008 - Policy violation */
        val POLICY_VIOLATION = CloseCode(1008u)

        /** 1009 - Message too big */
        val MESSAGE_TOO_BIG = CloseCode(1009u)

        /** 1010 - Missing expected extension (also known as MANDATORY_EXTENSION) */
        val MISSING_EXTENSION = CloseCode(1010u)

        /** 1011 - Internal server error */
        val INTERNAL_ERROR = CloseCode(1011u)
    }
}
