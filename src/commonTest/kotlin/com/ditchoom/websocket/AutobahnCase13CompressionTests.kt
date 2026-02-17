package com.ditchoom.websocket

import kotlin.test.Test

/**
 * Autobahn Category 13: Compression window bits negotiation.
 *
 * All subcategories echo 1000 text messages with compression enabled.
 * Each subcategory covers 18 cases (3 payload sizes × 6 variants).
 *
 * Subcategories:
 * - 13.1 (392-409): Default compression options
 * - 13.2 (410-427): Default compression options
 * - 13.3 (428-445): server_max_window_bits=9, context takeover, client offers clientMaxWindowBits
 * - 13.4 (446-463): server_max_window_bits=15, context takeover, client offers clientMaxWindowBits
 * - 13.5 (464-481): server_max_window_bits=9, no-context-takeover, client offers clientMaxWindowBits
 * - 13.6 (482-499): server_max_window_bits=15, no-context-takeover, client offers clientMaxWindowBits
 * - 13.7 (500-517): Default compression options
 */
class AutobahnCase13CompressionTests {
    private val windowBitsOpts =
        CompressionOptions(
            clientNoContextTakeover = false,
            serverNoContextTakeover = false,
            clientMaxWindowBits = -1,
        )
    private val windowBitsNoContextOpts = CompressionOptions(clientMaxWindowBits = -1)

    private fun echoBatch(
        caseRange: IntRange,
        opts: CompressionOptions = CompressionOptions(),
    ) = runHeavyCompressionTest {
        for (case in caseRange) {
            echoMessageAndClose(case, 1000, requestCompression = true, compressionOptions = opts)
        }
    }

    // 13.1-13.2: Default options (cases 392-427)
    @Test fun category13_1() = echoBatch(392..409)

    @Test fun category13_2() = echoBatch(410..427)

    // 13.3-13.4: Window bits, context takeover (cases 428-463)
    @Test fun category13_3() = echoBatch(428..445, windowBitsOpts)

    @Test fun category13_4() = echoBatch(446..463, windowBitsOpts)

    // 13.5-13.6: Window bits, no context takeover (cases 464-499)
    @Test fun category13_5() = echoBatch(464..481, windowBitsNoContextOpts)

    @Test fun category13_6() = echoBatch(482..499, windowBitsNoContextOpts)

    // 13.7: Default options (cases 500-517)
    @Test fun category13_7() = echoBatch(500..517)
}
