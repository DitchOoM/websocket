package com.ditchoom.websocket

import android.util.Base64
import kotlin.random.Random
import kotlin.random.nextInt

actual fun generateWebSocketKey(): String {
    val randomBytes = ByteArray(16) { Random.nextInt(97..122).toByte() }
    return Base64.encodeToString(randomBytes, Base64.DEFAULT)
}
