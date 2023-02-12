package com.ditchoom.websocket

actual fun generateWebSocketKey(): String =
    js("require('crypto').randomBytes(16).toString('base64')")
