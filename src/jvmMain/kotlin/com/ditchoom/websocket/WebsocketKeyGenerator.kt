package com.ditchoom.websocket

import java.util.Base64
import kotlin.random.Random
import kotlin.random.nextInt

actual fun generateWebSocketKey(): String =
    Base64.getEncoder().encodeToString(ByteArray(16) { Random.nextInt(97..122).toByte() })
