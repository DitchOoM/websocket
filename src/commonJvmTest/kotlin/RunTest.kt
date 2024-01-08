import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

actual fun <T> block(body: suspend CoroutineScope.() -> T) {
    runBlocking {
        withTimeout(3.seconds, body)
    }
}

actual fun agentName(): String {
    return try {
        Class.forName("android.os.Build")
        "Android"
    } catch (e: ClassNotFoundException) {
        "JVM"
    }
}
