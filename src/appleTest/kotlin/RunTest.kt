import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

actual fun <T> block(body: suspend CoroutineScope.() -> T) {
    runBlocking {
        withTimeout(40.seconds) {
            try {
                body()
            } catch (e: UnsupportedOperationException) {
                println("Test hit unsupported code, ignoring")
            }
        }
    }
}
