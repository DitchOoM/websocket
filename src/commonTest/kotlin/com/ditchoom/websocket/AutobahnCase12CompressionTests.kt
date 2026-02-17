package com.ditchoom.websocket

import kotlin.test.Test

/**
 * Autobahn Category 12: Compression with context takeover.
 *
 * All subcategories echo 1000 messages with compression enabled.
 * Tests are batched in groups of 6 cases to stay within the 600s timeout
 * on Linux K/N (which is ~2-3x slower than JVM for compression).
 *
 * Subcategories:
 * - 12.1 (302-319): Text echo, context takeover
 * - 12.2 (320-337): Binary echo, context takeover
 * - 12.3 (338-355): Binary echo, no context takeover
 * - 12.4 (356-373): Text echo, no context takeover
 * - 12.5 (374-391): Binary echo, mixed context takeover
 */
class AutobahnCase12CompressionTests {
    private fun textBatch(caseRange: IntRange) =
        runHeavyCompressionTest {
            for (case in caseRange) echoMessageAndClose(case, 1000, requestCompression = true)
        }

    private fun binaryBatch(caseRange: IntRange) =
        runHeavyCompressionTest {
            for (case in caseRange) echoBinaryMessageAndClose(case, 1000, requestCompression = true)
        }

    // 12.1: Text echo with context takeover (cases 302-319)
    @Test fun category12_1_a() = textBatch(302..307)

    @Test fun category12_1_b() = textBatch(308..313)

    @Test fun category12_1_c() = textBatch(314..319)

    // 12.2: Binary echo with context takeover (cases 320-337)
    @Test fun category12_2_a() = binaryBatch(320..325)

    @Test fun category12_2_b() = binaryBatch(326..331)

    @Test fun category12_2_c() = binaryBatch(332..337)

    // 12.3: Binary echo, no context takeover (cases 338-355)
    @Test fun category12_3_a() = binaryBatch(338..343)

    @Test fun category12_3_b() = binaryBatch(344..349)

    @Test fun category12_3_c() = binaryBatch(350..355)

    // 12.4: Text echo, no context takeover (cases 356-373)
    @Test fun category12_4_a() = textBatch(356..361)

    @Test fun category12_4_b() = textBatch(362..367)

    @Test fun category12_4_c() = textBatch(368..373)

    // 12.5: Binary echo, mixed context takeover (cases 374-391)
    @Test fun category12_5_a() = binaryBatch(374..379)

    @Test fun category12_5_b() = binaryBatch(380..385)

    @Test fun category12_5_c() = binaryBatch(386..391)
}
