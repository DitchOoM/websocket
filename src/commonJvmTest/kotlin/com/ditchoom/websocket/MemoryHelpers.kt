package com.ditchoom.websocket

internal actual fun forceGc() {
    System.gc()
    Thread.sleep(100)
    System.gc()
}

internal actual fun getUsedMemoryMB(): Long {
    val runtime = Runtime.getRuntime()
    return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
}
