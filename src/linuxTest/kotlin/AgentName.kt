import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

actual fun agentName(): String = "LinuxX64"

@OptIn(ExperimentalForeignApi::class)
actual fun autobahnHost(): String = getenv("AUTOBAHN_HOST")?.toKString() ?: "localhost"
