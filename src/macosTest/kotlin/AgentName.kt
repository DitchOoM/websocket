import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

actual fun agentName(): String = "macOS"

@OptIn(ExperimentalForeignApi::class)
actual fun autobahnHost(): String =
    getenv("AUTOBAHN_HOST")?.toKString() ?: "localhost"
