package com.ditchoom.websocket

import com.ditchoom.websocket.codecs.StringCodec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Mock Autobahn Category 4: Reserved Opcodes.
 *
 * RFC 6455 Section 5.2: Opcodes 0x3-0x7 (non-control) and 0xB-0xF (control)
 * are reserved. A client receiving a reserved opcode MUST close with 1002 (Protocol Error).
 */
class MockAutobahnCat4ReservedOpcodeTest {
    private fun testReservedOpcode(opcodeValue: Int) =
        runStrictTest {
            val transport = MockWebSocketTransport()
            val connection = MockAutobahnHelpers.connectWithHandshake(transport, StringCodec)

            // FIN=1 | opcode, payload length=0
            transport.enqueueReadBytes((0x80 or opcodeValue).toByte(), 0x00)

            withTimeout(5.seconds) { connection.receive().toList() }

            MockAutobahnHelpers.waitForWrite(transport, count = 2)
            MockAutobahnHelpers.assertClientSentClose(transport.writtenBuffers, 1002u)
            connection.close()
        }

    // Non-control reserved opcodes (0x3-0x7)
    @Test fun reservedOpcode0x3() = testReservedOpcode(0x3)

    @Test fun reservedOpcode0x4() = testReservedOpcode(0x4)

    @Test fun reservedOpcode0x5() = testReservedOpcode(0x5)

    @Test fun reservedOpcode0x6() = testReservedOpcode(0x6)

    @Test fun reservedOpcode0x7() = testReservedOpcode(0x7)

    // Control reserved opcodes (0xB-0xF)
    @Test fun reservedOpcode0xB() = testReservedOpcode(0xB)

    @Test fun reservedOpcode0xC() = testReservedOpcode(0xC)

    @Test fun reservedOpcode0xD() = testReservedOpcode(0xD)

    @Test fun reservedOpcode0xE() = testReservedOpcode(0xE)

    @Test fun reservedOpcode0xF() = testReservedOpcode(0xF)
}
