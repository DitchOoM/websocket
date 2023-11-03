import com.ditchoom.socket.isNodeJs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise

actual fun <T> block(body: suspend CoroutineScope.() -> T): dynamic = runTestInternal(block = body)

actual fun agentName(): String = if (isNodeJs) {
    "NodeJS"
} else {
    "BroswerJS"
}

fun <T> runTestInternal(
    block: suspend CoroutineScope.() -> T
): Promise<T?> {
    val promise = GlobalScope.promise {
        return@promise block()
    }
    return promise
}
