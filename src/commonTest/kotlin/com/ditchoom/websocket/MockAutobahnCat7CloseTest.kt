package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.websocket.codecs.StringCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Mock Autobahn Category 7: Close Handling.
 *
 * RFC 6455 Section 5.5.1: Tests close frame protocol compliance including
 * valid codes, invalid codes, payload edge cases, and UTF-8 in close reasons.
 */
class MockAutobahnCat7CloseTest {
    // ========================================================================
    // Valid close codes
    // ========================================================================

    private fun testValidClose(code: UShort) =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(transport, StringCodec)

            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(code))

            val msg =
                withTimeout(5.seconds) {
                    connection.receive().first()
                }
            assertIs<WebSocketMessage.Close>(msg)
            assertEquals(code, msg.code)
            connection.close()
        }

    @Test fun closeNormal1000() = testValidClose(1000u)

    @Test fun closeGoingAway1001() = testValidClose(1001u)

    @Test fun closeProtocolError1002() = testValidClose(1002u)

    @Test fun closeUnsupported1003() = testValidClose(1003u)

    @Test fun closeInvalidData1007() = testValidClose(1007u)

    @Test fun closePolicyViolation1008() = testValidClose(1008u)

    @Test fun closeMessageTooBig1009() = testValidClose(1009u)

    @Test fun closeMandatoryExtension1010() = testValidClose(1010u)

    @Test fun closeInternalError1011() = testValidClose(1011u)

    @Test fun closeCode3000() = testValidClose(3000u)

    @Test fun closeCode3999() = testValidClose(3999u)

    @Test fun closeCode4000() = testValidClose(4000u)

    @Test fun closeCode4999() = testValidClose(4999u)

    // ========================================================================
    // Close frame edge cases
    // ========================================================================

    @Test
    fun closeEmptyPayload() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(transport, StringCodec)

            // Close frame with no payload (no status code)
            val empty = BufferFactory.Default.allocate(0); empty.resetForRead()
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrameRaw(empty))

            val msg =
                withTimeout(5.seconds) {
                    connection.receive().first()
                }
            assertIs<WebSocketMessage.Close>(msg)
            connection.close()
        }

    @Test
    fun closeWithReasonString() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(transport, StringCodec)

            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u, "normal closure"))

            val msg =
                withTimeout(5.seconds) {
                    connection.receive().first()
                }
            assertIs<WebSocketMessage.Close>(msg)
            assertEquals(1000.toUShort(), msg.code)
            connection.close()
        }

    @Test
    fun close125BytePayload() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(transport, StringCodec)

            // 2 bytes code + 123 bytes reason = 125 bytes total (max for control frame)
            val reason = "A".repeat(123)
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u, reason))

            val msg =
                withTimeout(5.seconds) {
                    connection.receive().first()
                }
            assertIs<WebSocketMessage.Close>(msg)
            assertEquals(1000.toUShort(), msg.code)
            connection.close()
        }

    // ========================================================================
    // Invalid close frames → protocol error
    // ========================================================================

    @Test
    fun close1BytePayloadIsProtocolError() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(transport, StringCodec)

            // 1-byte close payload is invalid (code requires 2 bytes)
            val payload = BufferFactory.Default.allocate(1)
            payload.writeByte(0x42)
            payload.resetForRead()
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrameRaw(payload))

            withTimeout(5.seconds) { connection.receive().toList() }
            MockAutobahnHelpers.waitForWrite(transport, count = 2)
            MockAutobahnHelpers.assertClientSentClose(transport.writtenBuffers, 1002u)
            connection.close()
        }

    @Test
    fun close126BytePayloadIsProtocolError() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(transport, StringCodec)

            // Close with 126 bytes payload (exceeds 125 max for control frames)
            val payload = BufferFactory.Default.allocate(126)
            payload.writeShort(1000.toShort())
            for (i in 0 until 124) payload.writeByte(0x41)
            payload.resetForRead()
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrameRaw(payload))

            withTimeout(5.seconds) { connection.receive().toList() }
            MockAutobahnHelpers.waitForWrite(transport, count = 2)
            MockAutobahnHelpers.assertClientSentClose(transport.writtenBuffers, 1002u)
            connection.close()
        }

    @Test
    fun closeInvalidUtf8ReasonIsError() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(transport, StringCodec)

            // Close frame with code 1000 + invalid UTF-8 in reason
            val payload = BufferFactory.Default.allocate(4)
            payload.writeShort(1000.toShort())
            payload.writeByte(0xC0.toByte())
            payload.writeByte(0xAF.toByte())
            payload.resetForRead()
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrameRaw(payload))

            withTimeout(5.seconds) { connection.receive().toList() }
            MockAutobahnHelpers.waitForWrite(transport, count = 2)
            // Invalid UTF-8 in close reason → 1007 (INVALID_PAYLOAD)
            MockAutobahnHelpers.assertClientSentClose(transport.writtenBuffers, 1007u)
            connection.close()
        }

    // ========================================================================
    // Invalid close codes → protocol error
    // ========================================================================

    private fun testInvalidCloseCode(code: UShort) =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(transport, StringCodec)

            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(code))

            withTimeout(5.seconds) { connection.receive().toList() }
            MockAutobahnHelpers.waitForWrite(transport, count = 2)
            MockAutobahnHelpers.assertClientSentClose(transport.writtenBuffers, 1002u)
            connection.close()
        }

    @Test fun closeCode0IsProtocolError() = testInvalidCloseCode(0u)

    @Test fun closeCode999IsProtocolError() = testInvalidCloseCode(999u)

    @Test fun closeCode1004IsProtocolError() = testInvalidCloseCode(1004u)

    @Test fun closeCode1005OnWireIsProtocolError() = testInvalidCloseCode(1005u)

    @Test fun closeCode1006OnWireIsProtocolError() = testInvalidCloseCode(1006u)

    @Test fun closeCode1015OnWireIsProtocolError() = testInvalidCloseCode(1015u)

    @Test fun closeCode1016IsProtocolError() = testInvalidCloseCode(1016u)

    @Test fun closeCode2000IsProtocolError() = testInvalidCloseCode(2000u)

    @Test fun closeCode2999IsProtocolError() = testInvalidCloseCode(2999u)

    @Test fun closeCode5000IsProtocolError() = testInvalidCloseCode(5000u)
}
