package com.ditchoom.websocket

import kotlin.native.runtime.GC

@OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
internal actual fun forceGc() {
    GC.collect()
}

internal actual fun getUsedMemoryMB(): Long {
    // Kotlin/Native doesn't expose heap size directly
    return 0L
}
