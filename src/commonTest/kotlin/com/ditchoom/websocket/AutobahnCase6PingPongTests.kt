package com.ditchoom.websocket

import kotlin.test.Test

class AutobahnCase6PingPongTests {
    @Test
    fun category6_valid_utf8() =
        runTestNoTimeSkipping {
            // 6.1: Empty/zero-length fragments
            for (case in 65..67) echoMessageAndClose(case)
            // 6.2: Valid UTF-8 with fragmentation boundaries
            for (case in 68..71) echoMessageAndClose(case)
            // 6.5: Valid UTF-8 various codepoints
            for (case in 78..82) echoMessageAndClose(case)
            // 6.6 mixed: echo valid ones
            echoMessageAndClose(84)  // 6.6.2
            echoMessageAndClose(87)  // 6.6.5
            echoMessageAndClose(89)  // 6.6.7
            echoMessageAndClose(91)  // 6.6.9
            echoMessageAndClose(93)  // 6.6.11
            // 6.7: Valid edge cases
            for (case in 94..97) echoMessageAndClose(case)
            // 6.9: Valid continuation
            for (case in 100..103) echoMessageAndClose(case)
            // 6.11.1-6.11.4: Valid continuation edge cases
            for (case in 107..110) echoMessageAndClose(case)
            // 6.22: Valid comprehensive
            for (case in 169..202) echoMessageAndClose(case)
            // 6.23: Valid supplementary
            for (case in 203..209) echoMessageAndClose(case)
        }

    @Test
    fun category6_invalid_utf8() =
        runTestNoTimeSkipping {
            // 6.3: Invalid unfragmented
            for (case in 72..73) prepareConnection(case)
            // 6.4: Invalid with fragmentation
            for (case in 74..77) prepareConnection(case)
            // 6.6 mixed: reject invalid ones
            prepareConnection(83)   // 6.6.1
            prepareConnection(85)   // 6.6.3
            prepareConnection(86)   // 6.6.4
            prepareConnection(88)   // 6.6.6
            prepareConnection(90)   // 6.6.8
            prepareConnection(92)   // 6.6.10
            // 6.8: Invalid first byte
            for (case in 98..99) prepareConnection(case)
            // 6.10: Invalid continuation
            for (case in 104..106) prepareConnection(case)
            // 6.11.5: Invalid continuation edge case
            prepareConnection(111)
            // 6.12-6.21: Various invalid sequences
            for (case in 112..168) prepareConnection(case)
        }
}
