package com.ditchoom.websocket

internal actual fun forceGc() {
    // JS has automatic GC, no way to force it
}

internal actual fun getUsedMemoryMB(): Long {
    // JS doesn't expose memory usage in a standard way
    return 0L
}
