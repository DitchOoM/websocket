package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.shared
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Mock Autobahn Categories 3 + 6: UTF-8 Validation.
 *
 * RFC 6455 Section 5.6: Text frames contain UTF-8. Invalid UTF-8 MUST cause
 * close with 1007 (Invalid Payload Data).
 *
 * Tests valid sequences (client should emit Text message) and invalid sequences
 * (client should send close 1007).
 */
abstract class AbstractMockAutobahnCat6Test {
    abstract val bufferFactory: BufferFactory
    open val pool: BufferPool? get() = null

    @Test
    fun validAscii() = testValidUtf8("ASCII")

    @Test
    fun valid2ByteLatinEAcute() = testValidUtf8("2-byte Latin e-acute")

    @Test
    fun valid2ByteBoundaryU007F() = testValidUtf8("2-byte boundary U+007F")

    @Test
    fun valid2ByteBoundaryU0080() = testValidUtf8("2-byte boundary U+0080")

    @Test
    fun valid2ByteBoundaryU07FF() = testValidUtf8("2-byte boundary U+07FF")

    @Test
    fun valid3ByteCJK() = testValidUtf8("3-byte CJK")

    @Test
    fun valid3ByteBoundaryU0800() = testValidUtf8("3-byte boundary U+0800")

    @Test
    fun valid3ByteBoundaryUFFFF() = testValidUtf8("3-byte boundary U+FFFF")

    @Test
    fun valid4ByteEmoji() = testValidUtf8("4-byte emoji")

    @Test
    fun valid4ByteBoundaryU10000() = testValidUtf8("4-byte boundary U+10000")

    @Test
    fun valid4ByteBoundaryU10FFFF() = testValidUtf8("4-byte boundary U+10FFFF")

    @Test
    fun invalidLoneContinuation0x80() = testInvalidUtf8("lone continuation 0x80")

    @Test
    fun invalidLoneContinuation0xBF() = testInvalidUtf8("lone continuation 0xBF")

    @Test
    fun invalidOverlong2ByteSlash() = testInvalidUtf8("overlong 2-byte slash")

    @Test
    fun invalidOverlong2ByteC1() = testInvalidUtf8("overlong 2-byte C1")

    @Test
    fun invalidOverlong3Byte() = testInvalidUtf8("overlong 3-byte")

    @Test
    fun invalidOverlong4Byte() = testInvalidUtf8("overlong 4-byte")

    @Test
    fun invalidSurrogateUD800() = testInvalidUtf8("surrogate U+D800")

    @Test
    fun invalidSurrogateUDFFF() = testInvalidUtf8("surrogate U+DFFF")

    @Test
    fun invalidTooHighU110000() = testInvalidUtf8("too high U+110000")

    @Test
    fun invalidTruncated2Byte() = testInvalidUtf8("truncated 2-byte")

    @Test
    fun invalidTruncated3Byte() = testInvalidUtf8("truncated 3-byte")

    @Test
    fun invalidTruncated4Byte() = testInvalidUtf8("truncated 4-byte")

    @Test
    fun invalidStartByteFE() = testInvalidUtf8("invalid start byte FE")

    @Test
    fun invalidStartByteFF() = testInvalidUtf8("invalid start byte FF")

    @Test
    fun invalid5ByteSequenceF8() = testInvalidUtf8("5-byte sequence F8")

    @Test
    fun invalidUtf8InFragmentedText() =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(transport, bufferFactory = bufferFactory, pool = pool)
            MockAutobahnHelpers.connectWithHandshake(client, transport)

            // Valid first fragment, invalid continuation
            val validPart = BufferFactory.Default.allocate(3)
            validPart.writeByte(0x48); validPart.writeByte(0x65); validPart.writeByte(0x6C)
            validPart.resetForRead()
            val invalidPart = BufferFactory.Default.allocate(2)
            invalidPart.writeByte(0xC0.toByte()); invalidPart.writeByte(0xAF.toByte())
            invalidPart.resetForRead()

            transport.enqueueRead(
                MockAutobahnHelpers.buildServerFrame(Opcode.Text, validPart, fin = false),
            )
            transport.enqueueRead(
                MockAutobahnHelpers.buildServerContinuationFrame(invalidPart, fin = true),
            )
            // Server acknowledges close after client sends 1007
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1007u))

            withTimeout(5.seconds) { client.receive().toList() }
            MockAutobahnHelpers.waitForWrite(transport, count = 2)
            MockAutobahnHelpers.assertClientSentClose(transport.writtenBuffers, 1007u)
            client.close()
        }

    private fun testValidUtf8(name: String) =
        runStrictTest {
            val vectors = MockAutobahnHelpers.Utf8Vectors.valid()
            val (_, payload) = vectors.first { it.first == name }

            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(transport, bufferFactory = bufferFactory, pool = pool)
            MockAutobahnHelpers.connectWithHandshake(client, transport)

            transport.enqueueRead(
                MockAutobahnHelpers.buildServerFrame(Opcode.Text, payload),
            )
            transport.enqueueRead(MockAutobahnHelpers.buildServerCloseFrame(1000u))

            val msg =
                withTimeout(5.seconds) {
                    client.receive().first()
                }
            assertIs<WebSocketMessage.Text>(msg)
            client.close()
        }

    private fun testInvalidUtf8(name: String) =
        runStrictTest {
            val vectors = MockAutobahnHelpers.Utf8Vectors.invalid()
            val (_, payload) = vectors.first { it.first == name }

            val transport = MockWebSocketTransport()
            val client = MockAutobahnHelpers.createClient(transport, bufferFactory = bufferFactory, pool = pool)
            MockAutobahnHelpers.connectWithHandshake(client, transport)

            transport.enqueueRead(
                MockAutobahnHelpers.buildServerFrame(Opcode.Text, payload),
            )
            transport.enqueueReadError(Exception("done"))

            withTimeout(5.seconds) { client.receive().toList() }
            MockAutobahnHelpers.waitForWrite(transport, count = 2)
            MockAutobahnHelpers.assertClientSentClose(transport.writtenBuffers, 1007u)
            client.close()
        }
}

class MockAutobahnCat6DefaultTest : AbstractMockAutobahnCat6Test() {
    override val bufferFactory: BufferFactory = BufferFactory.Default
}

class MockAutobahnCat6ManagedTest : AbstractMockAutobahnCat6Test() {
    override val bufferFactory: BufferFactory = BufferFactory.managed()
}

class MockAutobahnCat6DeterministicTest : AbstractMockAutobahnCat6Test() {
    override val bufferFactory: BufferFactory = BufferFactory.deterministic()
}

class MockAutobahnCat6SharedTest : AbstractMockAutobahnCat6Test() {
    override val bufferFactory: BufferFactory = BufferFactory.shared()
}

class MockAutobahnCat6PooledTest : AbstractMockAutobahnCat6Test() {
    override val bufferFactory: BufferFactory = BufferFactory.managed()
    override val pool: BufferPool = BufferPool(factory = bufferFactory)
}
