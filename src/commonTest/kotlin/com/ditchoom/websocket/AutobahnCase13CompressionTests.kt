package com.ditchoom.websocket

import kotlin.test.Test

class AutobahnCase13CompressionTests {
    // Case 13.1: permessage-deflate with client_no_context_takeover + server_no_context_takeover
    @Test
    fun case13_1_1() = runHeavyCompressionTest { echoMessageAndClose(392, 1000, requestCompression = true) }

    @Test
    fun case13_1_2() = runHeavyCompressionTest { echoMessageAndClose(393, 1000, requestCompression = true) }

    @Test
    fun case13_1_3() = runHeavyCompressionTest { echoMessageAndClose(394, 1000, requestCompression = true) }

    @Test
    fun case13_1_4() = runHeavyCompressionTest { echoMessageAndClose(395, 1000, requestCompression = true) }

    @Test
    fun case13_1_5() = runHeavyCompressionTest { echoMessageAndClose(396, 1000, requestCompression = true) }

    @Test
    fun case13_1_6() = runHeavyCompressionTest { echoMessageAndClose(397, 1000, requestCompression = true) }

    @Test
    fun case13_1_7() = runHeavyCompressionTest { echoMessageAndClose(398, 1000, requestCompression = true) }

    @Test
    fun case13_1_8() = runHeavyCompressionTest { echoMessageAndClose(399, 1000, requestCompression = true) }

    @Test
    fun case13_1_9() = runHeavyCompressionTest { echoMessageAndClose(400, 1000, requestCompression = true) }

    @Test
    fun case13_1_10() = runHeavyCompressionTest { echoMessageAndClose(401, 1000, requestCompression = true) }

    @Test
    fun case13_1_11() = runHeavyCompressionTest { echoMessageAndClose(402, 1000, requestCompression = true) }

    @Test
    fun case13_1_12() = runHeavyCompressionTest { echoMessageAndClose(403, 1000, requestCompression = true) }

    @Test
    fun case13_1_13() = runHeavyCompressionTest { echoMessageAndClose(404, 1000, requestCompression = true) }

    @Test
    fun case13_1_14() = runHeavyCompressionTest { echoMessageAndClose(405, 1000, requestCompression = true) }

    @Test
    fun case13_1_15() = runHeavyCompressionTest { echoMessageAndClose(406, 1000, requestCompression = true) }

    @Test
    fun case13_1_16() = runHeavyCompressionTest { echoMessageAndClose(407, 1000, requestCompression = true) }

    @Test
    fun case13_1_17() = runHeavyCompressionTest { echoMessageAndClose(408, 1000, requestCompression = true) }

    @Test
    fun case13_1_18() = runHeavyCompressionTest { echoMessageAndClose(409, 1000, requestCompression = true) }

    // Case 13.2: permessage-deflate with client_no_context_takeover only
    @Test
    fun case13_2_1() = runHeavyCompressionTest { echoMessageAndClose(410, 1000, requestCompression = true) }

    @Test
    fun case13_2_2() = runHeavyCompressionTest { echoMessageAndClose(411, 1000, requestCompression = true) }

    @Test
    fun case13_2_3() = runHeavyCompressionTest { echoMessageAndClose(412, 1000, requestCompression = true) }

    @Test
    fun case13_2_4() = runHeavyCompressionTest { echoMessageAndClose(413, 1000, requestCompression = true) }

    @Test
    fun case13_2_5() = runHeavyCompressionTest { echoMessageAndClose(414, 1000, requestCompression = true) }

    @Test
    fun case13_2_6() = runHeavyCompressionTest { echoMessageAndClose(415, 1000, requestCompression = true) }

    @Test
    fun case13_2_7() = runHeavyCompressionTest { echoMessageAndClose(416, 1000, requestCompression = true) }

    @Test
    fun case13_2_8() = runHeavyCompressionTest { echoMessageAndClose(417, 1000, requestCompression = true) }

    @Test
    fun case13_2_9() = runHeavyCompressionTest { echoMessageAndClose(418, 1000, requestCompression = true) }

    @Test
    fun case13_2_10() = runHeavyCompressionTest { echoMessageAndClose(419, 1000, requestCompression = true) }

    @Test
    fun case13_2_11() = runHeavyCompressionTest { echoMessageAndClose(420, 1000, requestCompression = true) }

    @Test
    fun case13_2_12() = runHeavyCompressionTest { echoMessageAndClose(421, 1000, requestCompression = true) }

    @Test
    fun case13_2_13() = runHeavyCompressionTest { echoMessageAndClose(422, 1000, requestCompression = true) }

    @Test
    fun case13_2_14() = runHeavyCompressionTest { echoMessageAndClose(423, 1000, requestCompression = true) }

    @Test
    fun case13_2_15() = runHeavyCompressionTest { echoMessageAndClose(424, 1000, requestCompression = true) }

    @Test
    fun case13_2_16() = runHeavyCompressionTest { echoMessageAndClose(425, 1000, requestCompression = true) }

    @Test
    fun case13_2_17() = runHeavyCompressionTest { echoMessageAndClose(426, 1000, requestCompression = true) }

    @Test
    fun case13_2_18() = runHeavyCompressionTest { echoMessageAndClose(427, 1000, requestCompression = true) }

    // Cases 13.3-13.6 (case numbers 428-499) require different compression parameter offers
    // (e.g., server_no_context_takeover only, no params, client_max_window_bits, server_max_window_bits)
    // that our client doesn't currently support. These are excluded in fuzzingserver.json.

    // Case 13.7: permessage-deflate with all parameters
    @Test
    fun case13_7_1() = runHeavyCompressionTest { echoMessageAndClose(500, 1000, requestCompression = true) }

    @Test
    fun case13_7_2() = runHeavyCompressionTest { echoMessageAndClose(501, 1000, requestCompression = true) }

    @Test
    fun case13_7_3() = runHeavyCompressionTest { echoMessageAndClose(502, 1000, requestCompression = true) }

    @Test
    fun case13_7_4() = runHeavyCompressionTest { echoMessageAndClose(503, 1000, requestCompression = true) }

    @Test
    fun case13_7_5() = runHeavyCompressionTest { echoMessageAndClose(504, 1000, requestCompression = true) }

    @Test
    fun case13_7_6() = runHeavyCompressionTest { echoMessageAndClose(505, 1000, requestCompression = true) }

    @Test
    fun case13_7_7() = runHeavyCompressionTest { echoMessageAndClose(506, 1000, requestCompression = true) }

    @Test
    fun case13_7_8() = runHeavyCompressionTest { echoMessageAndClose(507, 1000, requestCompression = true) }

    @Test
    fun case13_7_9() = runHeavyCompressionTest { echoMessageAndClose(508, 1000, requestCompression = true) }

    @Test
    fun case13_7_10() = runHeavyCompressionTest { echoMessageAndClose(509, 1000, requestCompression = true) }

    @Test
    fun case13_7_11() = runHeavyCompressionTest { echoMessageAndClose(510, 1000, requestCompression = true) }

    @Test
    fun case13_7_12() = runHeavyCompressionTest { echoMessageAndClose(511, 1000, requestCompression = true) }

    @Test
    fun case13_7_13() = runHeavyCompressionTest { echoMessageAndClose(512, 1000, requestCompression = true) }

    @Test
    fun case13_7_14() = runHeavyCompressionTest { echoMessageAndClose(513, 1000, requestCompression = true) }

    @Test
    fun case13_7_15() = runHeavyCompressionTest { echoMessageAndClose(514, 1000, requestCompression = true) }

    @Test
    fun case13_7_16() = runHeavyCompressionTest { echoMessageAndClose(515, 1000, requestCompression = true) }

    @Test
    fun case13_7_17() = runHeavyCompressionTest { echoMessageAndClose(516, 1000, requestCompression = true) }

    @Test
    fun case13_7_18() = runHeavyCompressionTest { echoMessageAndClose(517, 1000, requestCompression = true) }
}
