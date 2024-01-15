
import com.ditchoom.socket.isNodeJs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import kotlin.js.Promise
import kotlin.time.Duration.Companion.seconds

actual fun <T> block(body: suspend CoroutineScope.() -> T): dynamic = runTestInternal(block = body)

actual fun agentName(): String = if (isNodeJs) {
    "NodeJS"
} else {
    "BrowserJS"
}

fun <T> runTestInternal(
    block: suspend CoroutineScope.() -> T
): Promise<T?> {
    val promise = GlobalScope.promise {
        return@promise withTimeout(60.seconds, block)
    }
    return promise
}
