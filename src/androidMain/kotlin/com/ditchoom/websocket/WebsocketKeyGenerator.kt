package com.ditchoom.websocket

import android.annotation.SuppressLint
import android.os.Build
import android.util.Base64
import kotlin.random.Random
import kotlin.random.nextInt

@SuppressLint("NewApi")
actual fun generateWebSocketKey(): String {
    val randomBytes = ByteArray(16) { Random.nextInt(97..122).toByte() }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || Build.VERSION.SDK_INT == 0) {
        java.util.Base64
            .getEncoder()
            .encodeToString(randomBytes)
    } else {
        Base64.encodeToString(randomBytes, Base64.DEFAULT)
    }
}
