package com.ditchoom.websocket

import agentName
import block
import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.EMPTY_BUFFER
import com.ditchoom.socket.connect
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class AutobahnTests {
    enum class AutobahnConnectivityState {
        UNTESTED,
        AVAILABLE,
        UNAVAILABLE
    }

    private var autobahnState = AutobahnConnectivityState.UNTESTED

    fun <T> maybeRun(lambda: suspend CoroutineScope.() -> T) {
        when (autobahnState) {
            AutobahnConnectivityState.UNTESTED -> {
                block {
                    autobahnState = try {
                        ClientSocket.connect(9001, "localhost", tls = false, 3.seconds)
                        AutobahnConnectivityState.AVAILABLE
                    } catch (e: Exception) {
                        AutobahnConnectivityState.UNAVAILABLE
                    }
                    maybeRun(lambda)
                }
            }

            AutobahnConnectivityState.AVAILABLE -> {
                try {
                    block(lambda)
                } catch (e: TimeoutCancellationException) {
                    // try again
                    println("Trying again!")
                    try {
                        block(lambda)
                    } catch (e: TimeoutCancellationException) {
                        println("Try one last time!")
                        block(lambda)
                    }
                }
            }

            AutobahnConnectivityState.UNAVAILABLE -> {
                println("Autobahn Docker Image Unavailable, ignoring test.")
            } // Do nothing
        }
    }

    @Test
    fun case1_1() = maybeRun {
        val webSocketClient = prepareConnection(1)
        println("test case 1.1.1")
        sendMessageWithPayloadLengthOf(webSocketClient, 0)
        println("close connection")
        closeConnection(webSocketClient)
        println("done test case 1.1.1")

        println("test case 1.1.2")
        val webSocket1Client = prepareConnection(2)
        sendMessageWithPayloadLengthOf(webSocket1Client, 125)
        closeConnection(webSocket1Client)
        println("close test case 2")

        println("test case 1.1.3")
        val webSocket3Client = prepareConnection(3)
        sendMessageWithPayloadLengthOf(webSocket3Client, 126)
        closeConnection(webSocket3Client)
        println("close test case 3")

        println("test case 1.1.4")
        val webSocket4Client = prepareConnection(4)
        sendMessageWithPayloadLengthOf(webSocket4Client, 127)
        closeConnection(webSocket4Client)
        println("close test case 4")

        println("test case 1.1.5")
        val webSocket5Client = prepareConnection(5)
        sendMessageWithPayloadLengthOf(webSocket5Client, 128)
        closeConnection(webSocket5Client)
        println("close test case 5")

        // 65535
        println("test case 1.1.6")
        val webSocket6Client = prepareConnection(6)
        sendMessageWithPayloadLengthOf(webSocket6Client, 65535)
        closeConnection(webSocket6Client)
        println("close test case 6")

        println("test case 1.1.7")
        val webSocket7Client = prepareConnection(7)
        sendMessageWithPayloadLengthOf(webSocket7Client, 65536)
        closeConnection(webSocket7Client)
        println("close test case 7")

        println("test case 1.1.8")
        val webSocket8Client = prepareConnection(8)
        sendMessageWithPayloadLengthOf(webSocket8Client, 65536)
        closeConnection(webSocket8Client)
        println("close test case 8")
    }

    @Test
    fun case1_2() = maybeRun {
        println("test case 1.2.1")
        val webSocketClient = prepareConnection(9)
        sendBinaryWithPayloadLengthOf(webSocketClient, 0)
        closeConnection(webSocketClient)

        println("test case 1.2.2")
        val webSocket1Client = prepareConnection(10)
        sendBinaryWithPayloadLengthOf(webSocket1Client, 125)
        closeConnection(webSocket1Client)
        println("close test case 2")

        println("test case 1.2.3")
        val webSocket3Client = prepareConnection(11)
        sendBinaryWithPayloadLengthOf(webSocket3Client, 126)
        closeConnection(webSocket3Client)
        println("close test case 3")

        println("test case 1.2.4")
        val webSocket4Client = prepareConnection(12)
        sendBinaryWithPayloadLengthOf(webSocket4Client, 127)
        closeConnection(webSocket4Client)
        println("close test case 4")

        println("test case 1.2.5")
        val webSocket5Client = prepareConnection(13)
        sendBinaryWithPayloadLengthOf(webSocket5Client, 128)
        closeConnection(webSocket5Client)
        println("close test case 5")

        // 65535
        println("test case 1.2.6")
        val webSocket6Client = prepareConnection(14)
        sendBinaryWithPayloadLengthOf(webSocket6Client, 65535)
        closeConnection(webSocket6Client)
        println("close test case 6")

        println("test case 1.2.7")
        val webSocket7Client = prepareConnection(15)
        sendBinaryWithPayloadLengthOf(webSocket7Client, 65536)
        closeConnection(webSocket7Client)
        println("close test case 7")

        println("test case 1.2.8")
        val webSocket8Client = prepareConnection(16)
        sendBinaryWithPayloadLengthOf(webSocket8Client, 65536)
        closeConnection(webSocket8Client)
        println("close test case 8")
    }

    @Test
    fun case2_1() = maybeRun {
        println("test case 2.1")
        prepareConnection(17)
        println("close test case 2.1")

        println("test case 2.2")
        prepareConnection(18)
        println("close test case 2.2")

        println("test case 2.3")
        prepareConnection(19)
        println("close test case 2.3")

        println("test case 2.4")
        prepareConnection(20)
        println("close test case 2.4")

        println("test case 2.5")
        prepareConnection(21)
        println("close test case 2.5")

        println("test case 2.6")
        prepareConnection(22)
        println("close test case 2.6")

        println("test case 2.7")
        prepareConnection(23)
        println("close test case 2.7")

        println("test case 2.8")
        prepareConnection(24)
        println("close test case 2.8")

        println("test case 2.9")
        prepareConnection(25)
        println("close test case 2.9")

        println("test case 2.10")
        prepareConnection(26)
        println("close test case 2.10")

        println("test case 2.11")
        prepareConnection(27)
        println("close test case 2.11")
    }

    @Test
    fun case3_1() = maybeRun {
        println("test case 3.1")
        prepareConnection(28)
        println("close test case 3.1")

        println("test case 3.2")
        prepareConnection(29)
        println("close test case 3.2")

        println("test case 3.3")
        prepareConnection(30)
        println("close test case 3.3")

        println("test case 3.4")
        prepareConnection(31)
        println("close test case 3.4")

        println("test case 3.5")
        prepareConnection(32)
        println("close test case 3.5")

        println("test case 3.6")
        prepareConnection(33)
        println("close test case 3.6")

        println("test case 3.7")
        prepareConnection(34)
        println("close test case 3.7")
    }

    @Test
    fun case4_1() = maybeRun {
        println("test case 4.1.1")
        prepareConnection(35)
        println("close test case 4.1.1")

        println("test case 4.1.2")
        prepareConnection(36)
        println("close test case 4.1.2")

        println("test case 4.1.3")
        prepareConnection(37)
        println("close test case 4.1.3")

        println("test case 4.1.4")
        prepareConnection(38)
        println("close test case 4.1.4")

        println("test case 4.1.5")
        prepareConnection(39)
        println("close test case 4.1.5")
        delay(100) // need to wait for the remote to close the connection
    }

    @Test
    fun case4_2() = maybeRun {
        println("test case 4.2.1")
        prepareConnection(40)
        println("close test case 4.2.1")

        println("test case 4.2.2")
        prepareConnection(41)
        println("close test case 4.2.2")

        println("test case 4.2.3")
        prepareConnection(42)
        println("close test case 4.2.3")

        println("test case 4.2.4")
        prepareConnection(43)
        println("close test case 4.2.4")

        println("test case 4.2.5")
        prepareConnection(44)
        println("close test case 4.2.5")
        delay(100) // need to wait for the remote to close the connection
    }

    @Test
    fun case5() = maybeRun {
        println("test case 5.1")
        prepareConnection(45)
        println("close test case 5.1")

        println("test case 5.2")
        prepareConnection(46)
        println("close test case 5.2")

        println("test case 5.3")
        echoMessageAndClose(47)
        println("close test case 5.3")

        println("test case 5.4")
        echoMessageAndClose(48)
        println("close test case 5.4")

        println("test case 5.5")
        echoMessageAndClose(49)
        println("close test case 5.5")

        println("test case 5.6")
        echoMessageWhenFoundText(50)
        println("close test case 5.6")

        println("test case 5.7")
        echoMessageWhenFoundText(51)
        println("close test case 5.7")

        println("test case 5.8")
        echoMessageWhenFoundText(52)
        println("close test case 5.8")

        println("test case 5.9")
        closeConnection(prepareConnection(53))
        println("close test case 5.9")

        println("test case 5.10")
        closeConnection(prepareConnection(54))
        println("close test case 5.10")

        println("test case 5.11")
        closeConnection(prepareConnection(55))
        println("close test case 5.11")

        println("test case 5.12")
        closeConnection(prepareConnection(56))
        println("close test case 5.12")

        println("test case 5.13")
        closeConnection(prepareConnection(57))
        println("close test case 5.13")

        println("test case 5.14")
        closeConnection(prepareConnection(58))
        println("close test case 5.14")

        println("test case 5.15")
        closeConnection(prepareConnection(59))
        println("close test case 5.15")

        println("test case 5.16")
        closeConnection(prepareConnection(60))
        println("close test case 5.16")

        println("test case 5.17")
        closeConnection(prepareConnection(61))
        println("close test case 5.17")

        println("test case 5.18")
        closeConnection(prepareConnection(62))
        println("close test case 5.18")

        println("test case 5.19")
        echoMessageWhenFoundText(63)
        println("close test case 5.19")

        println("test case 5.20")
        echoMessageWhenFoundText(64)
        println("close test case 5.20")
    }

    @Test
    fun case6_1() = maybeRun {
        println("test case 6.1.1")
        echoMessageAndClose(65)
        println("close test case 6.1.1")

        println("test case 6.1.2")
        echoMessageAndClose(66)
        println("close test case 6.1.2")

        println("test case 6.1.3")
        echoMessageAndClose(67)
        println("close test case 6.1.3")
    }

    @Test
    fun case6_2() = maybeRun {
        println("test case 6.2.1")
        echoMessageAndClose(68)
        println("close test case 6.2.1")

        println("test case 6.2.2")
        echoMessageAndClose(69)
        println("close test case 6.2.2")

        println("test case 6.2.3")
        echoMessageAndClose(70)
        println("close test case 6.2.3")

        println("test case 6.2.4")
        echoMessageAndClose(71)
        println("close test case 6.2.4")
    }

    @Test
    fun case6_3() = maybeRun {
        println("test case 6.3.1")
        prepareConnection(72)
        println("close test case 6.3.1")

        println("test case 6.3.2")
        prepareConnection(73)
        println("close test case 6.3.2")
    }

    @Test
    fun case6_4() = maybeRun {
        println("test case 6.4.1")
        prepareConnection(74)
        println("close test case 6.4.1")

        println("test case 6.4.2")
        prepareConnection(75)
        println("close test case 6.4.2")

        println("test case 6.4.3")
        prepareConnection(76)
        println("close test case 6.4.3")

        println("test case 6.4.4")
        prepareConnection(77)
        println("close test case 6.4.4")
    }

    @Test
    fun case6_5() = maybeRun {
        println("test case 6.5.1")
        echoMessageAndClose(78)
        println("close test case 6.5.1")

        println("test case 6.5.2")
        echoMessageAndClose(79)
        println("close test case 6.5.2")

        println("test case 6.5.3")
        echoMessageAndClose(80)
        println("close test case 6.5.3")

        println("test case 6.5.4")
        echoMessageAndClose(81)
        println("close test case 6.5.4")

        println("test case 6.5.5")
        echoMessageAndClose(82)
        println("close test case 6.5.5")
    }

    @Test
    fun case6_6() = maybeRun {
        println("test case 6.6.1")
        prepareConnection(83)
        println("close test case 6.6.1")

        println("test case 6.6.2")
        echoMessageAndClose(84)
        println("close test case 6.6.2")

        println("test case 6.6.3")
        prepareConnection(85)
        println("close test case 6.6.3")

        println("test case 6.6.4")
        prepareConnection(86)
        println("close test case 6.6.4")

        println("test case 6.6.5")
        echoMessageAndClose(87)
        println("close test case 6.6.5")

        println("test case 6.6.6")
        prepareConnection(88)
        println("close test case 6.6.6")

        println("test case 6.6.7")
        echoMessageAndClose(89)
        println("close test case 6.6.7")

        println("test case 6.6.8")
        prepareConnection(90)
        println("close test case 6.6.8")

        println("test case 6.6.9")
        echoMessageAndClose(91)
        println("close test case 6.6.9")

        println("test case 6.6.10")
        prepareConnection(92)
        println("close test case 6.6.10")

        println("test case 6.6.11")
        echoMessageAndClose(93)
        println("close test case 6.6.11")
    }

    @Test
    fun case6_7() = maybeRun {
        println("test case 6.7.1")
        echoMessageAndClose(94)
        println("close test case 6.7.1")

        println("test case 6.7.2")
        echoMessageAndClose(95)
        println("close test case 6.7.2")

        println("test case 6.7.3")
        echoMessageAndClose(96)
        println("close test case 6.7.3")

        println("test case 6.7.4")
        echoMessageAndClose(97)
        println("close test case 6.7.4")
    }

    @Test
    fun case6_8() = maybeRun {
        println("test case 6.8.1")
        prepareConnection(98)
        println("close test case 6.8.1")

        println("test case 6.8.2")
        prepareConnection(99)
        println("close test case 6.8.2")
    }

    @Test
    fun case6_9() = maybeRun {
        println("test case 6.9.1")
        echoMessageAndClose(100)
        println("close test case 6.9.1")

        println("test case 6.9.2")
        echoMessageAndClose(101)
        println("close test case 6.9.2")

        println("test case 6.9.3")
        echoMessageAndClose(102)
        println("close test case 6.9.3")

        println("test case 6.9.4")
        echoMessageAndClose(103)
        println("close test case 6.9.4")
    }

    @Test
    fun case6_10() = maybeRun {
        println("test case 6.10.1")
        prepareConnection(104)
        println("close test case 6.10.1")

        println("test case 6.10.2")
        prepareConnection(105)
        println("close test case 6.10.2")

        println("test case 6.10.3")
        prepareConnection(106)
        println("close test case 6.10.3")
    }

    @Test
    fun case6_11() = maybeRun {
        println("test case 6.11.1")
        echoMessageAndClose(107)
        println("close test case 6.11.1")

        println("test case 6.11.2")
        echoMessageAndClose(108)
        println("close test case 6.11.2")

        println("test case 6.11.3")
        echoMessageAndClose(109)
        println("close test case 6.10.3")

        println("test case 6.11.4")
        echoMessageAndClose(110)
        println("close test case 6.10.4")

        println("test case 6.11.5")
        prepareConnection(111)
        println("close test case 6.10.5")
    }

    @Test
    fun case6_12() = maybeRun {
        println("test case 6.12.1")
        prepareConnection(112)
        println("close test case 6.12.1")

        println("test case 6.12.2")
        prepareConnection(113)
        println("close test case 6.12.2")

        println("test case 6.12.3")
        prepareConnection(114)
        println("close test case 6.12.3")

        println("test case 6.12.4")
        prepareConnection(115)
        println("close test case 6.12.4")

        println("test case 6.12.5")
        prepareConnection(116)
        println("close test case 6.12.5")

        println("test case 6.12.6")
        prepareConnection(117)
        println("close test case 6.12.6")

        println("test case 6.12.7")
        prepareConnection(118)
        println("close test case 6.12.7")

        println("test case 6.12.8")
        prepareConnection(119)
        println("close test case 6.12.8")
    }

    @Test
    fun case6_13() = maybeRun {
        println("test case 6.13.1")
        prepareConnection(120)
        println("close test case 6.13.1")

        println("test case 6.13.2")
        prepareConnection(121)
        println("close test case 6.13.2")

        println("test case 6.13.3")
        prepareConnection(122)
        println("close test case 6.13.3")

        println("test case 6.13.4")
        prepareConnection(123)
        println("close test case 6.13.4")

        println("test case 6.13.5")
        prepareConnection(124)
        println("close test case 6.13.5")
    }

    @Test
    fun case6_14() = maybeRun {
        println("test case 6.14.1")
        prepareConnection(125)
        println("close test case 6.14.1")

        println("test case 6.14.2")
        prepareConnection(126)
        println("close test case 6.14.2")

        println("test case 6.14.3")
        prepareConnection(127)
        println("close test case 6.14.3")

        println("test case 6.14.4")
        prepareConnection(128)
        println("close test case 6.14.4")

        println("test case 6.14.5")
        prepareConnection(129)
        println("close test case 6.14.5")

        println("test case 6.14.6")
        prepareConnection(130)
        println("close test case 6.14.6")

        println("test case 6.14.7")
        prepareConnection(131)
        println("close test case 6.14.7")

        println("test case 6.14.8")
        prepareConnection(132)
        println("close test case 6.14.8")

        println("test case 6.14.9")
        prepareConnection(133)
        println("close test case 6.14.9")

        println("test case 6.14.10")
        prepareConnection(134)
        println("close test case 6.14.10")
    }

    @Test
    fun case6_15() = maybeRun {
        println("test case 6.15.1")
        prepareConnection(135)
        println("close test case 6.15.1")
    }

    @Test
    fun case6_16() = maybeRun {
        println("test case 6.16.1")
        prepareConnection(136)
        println("close test case 6.16.1")

        println("test case 6.16.2")
        prepareConnection(137)
        println("close test case 6.16.2")

        println("test case 6.16.3")
        prepareConnection(138)
        println("close test case 6.16.3")
    }

    @Test
    fun case6_17() = maybeRun {
        println("test case 6.17.1")
        prepareConnection(139)
        println("close test case 6.17.1")

        println("test case 6.17.2")
        prepareConnection(140)
        println("close test case 6.17.2")

        println("test case 6.17.3")
        prepareConnection(141)
        println("close test case 6.17.3")

        println("test case 6.17.4")
        prepareConnection(142)
        println("close test case 6.17.4")

        println("test case 6.17.5")
        prepareConnection(143)
        println("close test case 6.17.5")
    }

    @Test
    fun case6_18() = maybeRun {
        println("test case 6.18.1")
        prepareConnection(144)
        println("close test case 6.18.1")

        println("test case 6.18.2")
        prepareConnection(145)
        println("close test case 6.18.2")

        println("test case 6.18.3")
        prepareConnection(146)
        println("close test case 6.18.3")

        println("test case 6.18.4")
        prepareConnection(147)
        println("close test case 6.18.4")

        println("test case 6.18.5")
        prepareConnection(148)
        println("close test case 6.18.5")
    }

    @Test
    fun case6_19() = maybeRun {
        println("test case 6.18.1")
        prepareConnection(149)
        println("close test case 6.18.1")

        println("test case 6.18.2")
        prepareConnection(150)
        println("close test case 6.18.2")

        println("test case 6.18.3")
        prepareConnection(151)
        println("close test case 6.18.3")

        println("test case 6.18.4")
        prepareConnection(152)
        println("close test case 6.18.4")

        println("test case 6.18.5")
        prepareConnection(153)
        println("close test case 6.18.5")
    }

    @Test
    fun case6_20() = maybeRun {
        println("test case 6.20.1")
        prepareConnection(154)
        println("close test case 6.20.1")

        println("test case 6.20.2")
        prepareConnection(155)
        println("close test case 6.20.2")

        println("test case 6.20.3")
        prepareConnection(156)
        println("close test case 6.20.3")

        println("test case 6.20.4")
        prepareConnection(157)
        println("close test case 6.20.4")

        println("test case 6.20.5")
        prepareConnection(158)
        println("close test case 6.20.5")

        println("test case 6.20.6")
        prepareConnection(159)
        println("close test case 6.20.6")

        println("test case 6.20.7")
        prepareConnection(160)
        println("close test case 6.20.7")
    }

    @Test
    fun case6_21() = maybeRun {
        println("test case 6.21.1")
        prepareConnection(161)
        println("close test case 6.21.1")

        println("test case 6.21.2")
        prepareConnection(162)
        println("close test case 6.21.2")

        println("test case 6.21.3")
        prepareConnection(163)
        println("close test case 6.21.3")

        println("test case 6.21.4")
        prepareConnection(164)
        println("close test case 6.21.4")

        println("test case 6.21.5")
        prepareConnection(165)
        println("close test case 6.21.5")

        println("test case 6.21.6")
        prepareConnection(166)
        println("close test case 6.21.6")

        println("test case 6.21.7")
        prepareConnection(167)
        println("close test case 6.21.7")

        println("test case 6.21.8")
        prepareConnection(168)
        println("close test case 6.21.8")
    }

    @Test
    fun case6_22() = maybeRun {
        println("test case 6.22.1")
        echoMessageAndClose(169)
        println("close test case 6.22.1")

        println("test case 6.22.2")
        echoMessageAndClose(170)
        println("close test case 6.22.2")

        println("test case 6.22.3")
        echoMessageAndClose(171)
        println("close test case 6.22.3")

        println("test case 6.22.4")
        echoMessageAndClose(172)
        println("close test case 6.22.4")

        println("test case 6.22.5")
        echoMessageAndClose(173)
        println("close test case 6.22.5")

        println("test case 6.22.6")
        echoMessageAndClose(174)
        println("close test case 6.22.6")

        println("test case 6.22.7")
        echoMessageAndClose(175)
        println("close test case 6.22.7")

        println("test case 6.22.8")
        echoMessageAndClose(176)
        println("close test case 6.22.8")

        println("test case 6.22.9")
        echoMessageAndClose(177)
        println("close test case 6.22.9")

        println("test case 6.22.10")
        echoMessageAndClose(178)
        println("close test case 6.22.10")

        println("test case 6.22.11")
        echoMessageAndClose(179)
        println("close test case 6.22.11")

        println("test case 6.22.12")
        echoMessageAndClose(180)
        println("close test case 6.22.12")

        println("test case 6.22.13")
        echoMessageAndClose(181)
        println("close test case 6.22.13")

        println("test case 6.22.14")
        echoMessageAndClose(182)
        println("close test case 6.22.14")

        println("test case 6.22.15")
        echoMessageAndClose(183)
        println("close test case 6.22.15")

        println("test case 6.22.16")
        echoMessageAndClose(184)
        println("close test case 6.22.16")

        println("test case 6.22.17")
        echoMessageAndClose(185)
        println("close test case 6.22.17")

        println("test case 6.22.18")
        echoMessageAndClose(186)
        println("close test case 6.22.18")

        println("test case 6.22.19")
        echoMessageAndClose(187)
        println("close test case 6.22.19")

        println("test case 6.22.20")
        echoMessageAndClose(188)
        println("close test case 6.22.20")

        println("test case 6.22.21")
        echoMessageAndClose(189)
        println("close test case 6.22.21")

        println("test case 6.22.22")
        echoMessageAndClose(190)
        println("close test case 6.22.22")

        println("test case 6.22.23")
        echoMessageAndClose(191)
        println("close test case 6.22.23")

        println("test case 6.22.24")
        echoMessageAndClose(192)
        println("close test case 6.22.24")

        println("test case 6.22.25")
        echoMessageAndClose(193)
        println("close test case 6.22.25")

        println("test case 6.22.26")
        echoMessageAndClose(194)
        println("close test case 6.22.26")

        println("test case 6.22.27")
        echoMessageAndClose(195)
        println("close test case 6.22.27")

        println("test case 6.22.28")
        echoMessageAndClose(196)
        println("close test case 6.22.28")

        println("test case 6.22.29")
        echoMessageAndClose(197)
        println("close test case 6.22.29")

        println("test case 6.22.30")
        echoMessageAndClose(198)
        println("close test case 6.22.30")

        println("test case 6.22.31")
        echoMessageAndClose(199)
        println("close test case 6.22.31")

        println("test case 6.22.32")
        echoMessageAndClose(200)
        println("close test case 6.22.32")

        println("test case 6.22.33")
        echoMessageAndClose(201)
        println("close test case 6.22.33")

        println("test case 6.22.34")
        echoMessageAndClose(202)
        println("close test case 6.22.34")
    }

    @Test
    fun case6_23() = maybeRun {
        println("test case 6.23.1")
        echoMessageAndClose(203)
        println("close test case 6.23.1")

        println("test case 6.23.2")
        echoMessageAndClose(204)
        println("close test case 6.23.2")

        println("test case 6.23.3")
        echoMessageAndClose(205)
        println("close test case 6.23.3")

        println("test case 6.23.4")
        echoMessageAndClose(206)
        println("close test case 6.23.4")

        println("test case 6.23.5")
        echoMessageAndClose(207)
        println("close test case 6.23.5")

        println("test case 6.23.6")
        echoMessageAndClose(208)
        println("close test case 6.23.6")

        println("test case 6.23.7")
        echoMessageAndClose(209)
        println("close test case 6.23.7")
    }

    @Test
    fun case7_1() = maybeRun {
        println("test case 7.1.1")
        echoMessageAndClose(210)
        println("close test case 7.1.1")

        println("test case 7.1.2")
        prepareConnection(211)
        println("close test case 7.1.2")

        println("test case 7.1.3")
        prepareConnection(212)
        println("close test case 7.1.3")

        println("test case 7.1.4")
        prepareConnection(213)
        println("close test case 7.1.4")

        println("test case 7.1.5")
        prepareConnection(214)
        println("close test case 7.1.5")

        println("test case 7.1.6")
        prepareConnection(215)
        println("close test case 7.1.6")
    }

    @Test
    fun case7_3() = maybeRun {
        println("test case 7.3.1")
        closeConnection(prepareConnection(216))
        println("close test case 7.3.1")

        println("test case 7.3.2")
        prepareConnection(217)
        println("close test case 7.3.2")

        println("test case 7.3.3")
        closeConnection(prepareConnection(218))
        println("close test case 7.3.3")

        println("test case 7.3.4")
        closeConnection(prepareConnection(219))
        println("close test case 7.3.4")

        println("test case 7.3.5")
        closeConnection(prepareConnection(220))
        println("close test case 7.3.5")

        println("test case 7.3.6")
        prepareConnection(221)
        println("close test case 7.3.6")
    }

    @Test
    fun case7_5() = maybeRun {
        println("test case 7.5.1")
        prepareConnection(222)
        println("close test case 7.5.1")
    }

    @Test
    fun case7_7() = maybeRun {
        println("test case 7.7.1")
        prepareConnection(223)
        println("close test case 7.7.1")

        println("test case 7.7.2")
        prepareConnection(224)
        println("close test case 7.7.2")

        println("test case 7.7.3")
        prepareConnection(225)
        println("close test case 7.7.3")

        println("test case 7.7.4")
        prepareConnection(226)
        println("close test case 7.7.4")

        println("test case 7.7.5")
        prepareConnection(227)
        println("close test case 7.7.5")

        println("test case 7.7.6")
        prepareConnection(228)
        println("close test case 7.7.6")

        println("test case 7.7.7")
        prepareConnection(229)
        println("close test case 7.7.7")

        println("test case 7.7.8")
        prepareConnection(230)
        println("close test case 7.7.8")

        println("test case 7.7.9")
        prepareConnection(231)
        println("close test case 7.7.9")

        println("test case 7.7.10")
        prepareConnection(232)
        println("close test case 7.7.10")

        println("test case 7.7.11")
        prepareConnection(233)
        println("close test case 7.7.11")

        println("test case 7.7.12")
        prepareConnection(234)
        println("close test case 7.7.12")

        println("test case 7.7.13")
        prepareConnection(235)
        println("close test case 7.7.13")
    }

    @Test
    fun case7_9() = maybeRun {
        println("test case 7.9.1")
        prepareConnection(236)
        println("close test case 7.9.1")

        println("test case 7.9.2")
        prepareConnection(237)
        println("close test case 7.9.2")

        println("test case 7.9.3")
        prepareConnection(238)
        println("close test case 7.9.3")

        println("test case 7.9.4")
        prepareConnection(239)
        println("close test case 7.9.4")

        println("test case 7.9.5")
        prepareConnection(240)
        println("close test case 7.9.5")

        println("test case 7.9.6")
        prepareConnection(241)
        println("close test case 7.9.6")

        println("test case 7.9.7")
        prepareConnection(242)
        println("close test case 7.9.7")

        println("test case 7.9.8")
        prepareConnection(243)
        println("close test case 7.9.8")

        println("test case 7.9.9")
        prepareConnection(244)
        println("close test case 7.9.9")
    }

    @Test
    fun case7_13() = maybeRun {
        println("test case 7.13.1")
        prepareConnection(245)
        println("close test case 7.13.1")

        println("test case 7.13.2")
        prepareConnection(246)
        println("close test case 7.13.2")
    }

    @Test
    fun case9_1() = maybeRun {
        println("test case 9.1.1")
        echoMessageAndClose(247)
        println("close test case 9.1.1")

        println("test case 9.1.2")
        echoMessageAndClose(248)
        println("close test case 9.1.2")

        println("test case 9.1.3")
        echoMessageAndClose(249)
        println("close test case 9.1.3")

        println("test case 9.1.4")
        echoMessageAndClose(250)
        println("close test case 9.1.4")

        println("test case 9.1.5")
        echoMessageAndClose(251)
        println("close test case 9.1.5")

        println("test case 9.1.6")
        echoMessageAndClose(252)
        println("close test case 9.1.6")
    }

    @Test
    fun case9_2() = maybeRun {
        println("test case 9.2.1")
        echoBinaryMessageAndClose(253)
        println("close test case 9.2.1")

        println("test case 9.2.2")
        echoBinaryMessageAndClose(254)
        println("close test case 9.2.2")

        println("test case 9.2.3")
        echoBinaryMessageAndClose(255)
        println("close test case 9.2.3")

        println("test case 9.2.4")
        echoBinaryMessageAndClose(256)
        println("close test case 9.2.4")

        println("test case 9.2.5")
        echoBinaryMessageAndClose(257)
        println("close test case 9.2.5")

        println("test case 9.2.6")
        echoBinaryMessageAndClose(258)
        println("close test case 9.2.6")
    }

    @Test
    fun case9_3_1() = maybeRun {
        println("test case 9.3.1")
        echoMessageAndClose(259)
        println("close test case 9.3.1")
    }

    @Test
    fun case9_3_2() = maybeRun {
        println("test case 9.3.2")
        echoMessageAndClose(260)
        println("close test case 9.3.2")
    }

    @Test
    fun case9_3_3() = maybeRun {
        println("test case 9.3.3")
        echoMessageAndClose(261)
        println("close test case 9.3.3")
    }

    @Test
    fun case9_3_4() = maybeRun {
        println("test case 9.3.4")
        echoMessageAndClose(262)
        println("close test case 9.3.4")
    }

    @Test
    fun case9_3_5() = maybeRun {
        println("test case 9.3.5")
        echoMessageAndClose(263)
        println("close test case 9.3.5")
    }

    @Test
    fun case9_3_6() = maybeRun {
        println("test case 9.3.6")
        echoMessageAndClose(264)
        println("close test case 9.3.6")
    }

    @Test
    fun case9_3_7() = maybeRun {
        println("test case 9.3.7")
        echoMessageAndClose(265)
        println("close test case 9.3.7")
    }

    @Test
    fun case9_3_8() = maybeRun {
        println("test case 9.3.8")
        echoMessageAndClose(266)
        println("close test case 9.3.8")
    }

    @Test
    fun case9_3_9() = maybeRun {
        println("test case 9.3.9")
        echoMessageAndClose(267)
        println("close test case 9.3.9")
    }

    @Test
    fun case9_4_1() = maybeRun {
        println("test case 9.4.1")
        echoBinaryMessageAndClose(268)
        println("close test case 9.4.1")
    }

    @Test
    fun case9_4_2() = maybeRun {
        println("test case 9.4.2")
        echoBinaryMessageAndClose(269)
        println("close test case 9.4.2")
    }

    @Test
    fun case9_4_3() = maybeRun {
        println("test case 9.4.3")
        echoBinaryMessageAndClose(270)
        println("close test case 9.4.3")
    }

    @Test
    fun case9_4_4() = maybeRun {
        println("test case 9.4.4")
        echoBinaryMessageAndClose(271)
        println("close test case 9.4.4")
    }

    @Test
    fun case9_4_5() = maybeRun {
        println("test case 9.4.5")
        echoBinaryMessageAndClose(272)
        println("close test case 9.4.5")
    }

    @Test
    fun case9_4_6() = maybeRun {
        println("test case 9.4.6")
        echoBinaryMessageAndClose(273)
        println("close test case 9.4.6")
    }

    @Test
    fun case9_4_7() = maybeRun {
        println("test case 9.4.7")
        echoBinaryMessageAndClose(274)
        println("close test case 9.4.7")
    }

    @Test
    fun case9_4_8() = maybeRun {
        println("test case 9.4.8")
        echoBinaryMessageAndClose(275)
        println("close test case 9.4.8")
    }

    @Test
    fun case9_4_9() = maybeRun {
        println("test case 9.4.9")
        echoBinaryMessageAndClose(276)
        println("close test case 9.4.9")
    }

    @Test
    fun case9_5_1() = maybeRun {
        println("test case 9.5.1")
        echoMessageAndClose(277)
        println("close test case 9.5.1")
    }

    @Test
    fun case9_5_2() = maybeRun {
        println("test case 9.5.2")
        echoMessageAndClose(278)
        println("close test case 9.5.2")
    }

    @Test
    fun case9_5_3() = maybeRun {
        println("test case 9.5.3")
        echoMessageAndClose(279)
        println("close test case 9.5.3")
    }

    @Test
    fun case9_5_4() = maybeRun {
        println("test case 9.5.4")
        echoMessageAndClose(280)
        println("close test case 9.5.4")
    }

    @Test
    fun case9_5_5() = maybeRun {
        println("test case 9.5.5")
        echoMessageAndClose(281)
        println("close test case 9.5.5")
    }

    @Test
    fun case9_5_6() = maybeRun {
        println("test case 9.5.6")
        echoMessageAndClose(282)
        println("close test case 9.5.6")
    }

    @Test
    fun case9_6_1() = maybeRun {
        println("test case 9.6.1")
        echoBinaryMessageAndClose(283)
        println("close test case 9.6.1")
    }

    @Test
    fun case9_6_2() = maybeRun {
        println("test case 9.6.2")
        echoBinaryMessageAndClose(284)
        println("close test case 9.6.2")
    }

    @Test
    fun case9_6_3() = maybeRun {
        println("test case 9.6.3")
        echoBinaryMessageAndClose(285)
        println("close test case 9.6.3")
    }

    @Test
    fun case9_6_4() = maybeRun {
        println("test case 9.6.4")
        echoBinaryMessageAndClose(286)
        println("close test case 9.6.4")
    }

    @Test
    fun case9_6_5() = maybeRun {
        println("test case 9.6.5")
        echoBinaryMessageAndClose(287)
        println("close test case 9.6.5")
    }

    @Test
    fun case9_6_6() = maybeRun {
        println("test case 9.6.6")
        echoBinaryMessageAndClose(288)
        println("close test case 9.6.6")
    }

    @Test
    fun case9_7_1() = maybeRun {
        println("test case 9.7.1")
        echoMessageAndClose(289, 1000)
        println("close test case 9.7.1")
    }

    @Test
    fun case9_7_2() = maybeRun {
        println("test case 9.7.2")
        echoMessageAndClose(290, 1000)
        println("close test case 9.7.2")
    }

    @Test
    fun case9_7_3() = maybeRun {
        println("test case 9.7.3")
        echoMessageAndClose(291, 1000)
        println("close test case 9.7.3")
    }

    @Test
    fun case9_7_4() = maybeRun {
        println("test case 9.7.4")
        echoMessageAndClose(292, 1000)
        println("close test case 9.7.4")
    }

    @Test
    fun case9_7_5() = maybeRun {
        println("test case 9.7.5")
        echoMessageAndClose(293, 1000)
        println("close test case 9.7.5")
    }

    @Test
    fun case9_7_6() = maybeRun {
        println("test case 9.7.6")
        echoMessageAndClose(294, 1000)
        println("close test case 9.7.6")
    }

    @Test
    fun case9_8_1() = maybeRun {
        println("test case 9.8.1")
        echoBinaryMessageAndClose(295, 1000)
        println("close test case 9.8.1")
    }

    @Test
    fun case9_8_2() = maybeRun {
        println("test case 9.8.2")
        echoBinaryMessageAndClose(296, 1000)
        println("close test case 9.8.2")
    }

    @Test
    fun case9_8_3() = maybeRun {
        println("test case 9.8.3")
        echoBinaryMessageAndClose(297, 1000)
        println("close test case 9.8.3")
    }

    @Test
    fun case9_8_4() = maybeRun {
        println("test case 9.8.4")
        echoBinaryMessageAndClose(298, 1000)
        println("close test case 9.8.4")
    }

    @Test
    fun case9_8_5() = maybeRun {
        println("test case 9.8.5")
        echoBinaryMessageAndClose(299, 1000)
        println("close test case 9.8.5")
    }

    @Test
    fun case9_8_6() = maybeRun {
        println("test case 9.8.6")
        echoBinaryMessageAndClose(300, 1000)
        println("close test case 9.8.6")
    }

    @Test
    fun case10_1_1() = maybeRun {
        println("test case 10.1.1")
        echoMessageAndClose(301)
        println("close test case 10.1.1")
    }

    private suspend fun CoroutineScope.prepareConnection(case: Int): WebSocketClient {
        val connectionOptions = WebSocketConnectionOptions(
            name = "localhost",
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}"
        )
        val websocket = WebSocketClient.Companion.allocate(
            connectionOptions,
            AllocationZone.SharedMemory,
            this + CoroutineName(case.toString())
        )
        websocket.connect()
        websocket.connectionState.first { it is ConnectionState.Connected }
        return websocket
    }

    private suspend fun CoroutineScope.echoMessageAndClose(case: Int, count: Int = 1) {
        val connectionOptions = WebSocketConnectionOptions(
            name = "localhost",
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}"
        )
        val ws = WebSocketClient.Companion.allocate(
            connectionOptions,
            AllocationZone.SharedMemory,
            this + CoroutineName(case.toString())
        )
        ws.scope.launch {
            ws.connect()
            ws.connectionState.first { it is ConnectionState.Connected }
        }
        ws.incomingMessages.take(count).collect {
            val m = it as WebSocketMessage.Text
            ws.write(m.value)
        }
        ws.close()
    }

    private suspend fun CoroutineScope.echoMessageWhenFoundText(case: Int) {
        val connectionOptions = WebSocketConnectionOptions(
            name = "localhost",
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}"
        )
        val ws = WebSocketClient.Companion.allocate(
            connectionOptions,
            AllocationZone.SharedMemory,
            this + CoroutineName(case.toString())
        )
        ws.scope.launch {
            ws.connect()
            ws.connectionState.first { it is ConnectionState.Connected }
        }
        val msg = ws.incomingMessages.first { it is WebSocketMessage.Text } as WebSocketMessage.Text
        ws.write(msg.value)
        ws.close()
    }

    private suspend fun CoroutineScope.echoBinaryMessageAndClose(case: Int, count: Int = 1) {
        val connectionOptions = WebSocketConnectionOptions(
            name = "localhost",
            port = 9001,
            websocketEndpoint = "/runCase?case=$case&agent=${agentName()}"
        )
        val ws = WebSocketClient.Companion.allocate(
            connectionOptions,
            AllocationZone.SharedMemory,
            this + CoroutineName(case.toString())
        ) as DefaultWebSocketClient
        ws.scope.launch {
            ws.connect()
            ws.connectionState.first { it is ConnectionState.Connected }
        }
        ws.incomingMessages.take(count).collect {
            val m = it as WebSocketMessage.Binary
            ws.write(m.value)
        }
        ws.close()
    }

    suspend fun closeConnection(websocket: WebSocketClient) {
        websocket.close()
    }

    suspend fun sendMessageWithPayloadLengthOf(websocket: WebSocketClient, length: Int) {
        val string = if (length < 1) {
            ""
        } else {
            randomStringByKotlinRandom(length)
        }
        websocket.write(string)
    }

    suspend fun sendBinaryWithPayloadLengthOf(websocket: WebSocketClient, length: Int) {
        val binary = if (length < 1) {
            EMPTY_BUFFER
        } else {
            val b = PlatformBuffer.allocate(length)
            repeat(length) {
                b.writeByte(0xfe.toByte())
            }
            b.position(0)
            b
        }
        websocket.write(binary)
    }

    @AfterTest
    fun validateResponse() = block {
        println("** UPDATE REPORTS **")
        val connectionOptions = WebSocketConnectionOptions(
            name = "localhost",
            port = 9001,
            websocketEndpoint = "/updateReports?agent=${agentName()}"
        )
        val websocket = WebSocketClient.Companion.allocate(
            connectionOptions,
            AllocationZone.SharedMemory,
            this
        ) as DefaultWebSocketClient
        websocket.connect()
        websocket.connectionState.first { it is ConnectionState.Connected }
        websocket.close()
    }

    private val charPool: List<Char> = listOf('*') // ('a'..'z') + ('A'..'Z') + ('0'..'9')
    fun randomStringByKotlinRandom(len: Int) = (1..len)
        .map { Random.nextInt(0, charPool.size).let { charPool[it] } }
        .joinToString("")
}
