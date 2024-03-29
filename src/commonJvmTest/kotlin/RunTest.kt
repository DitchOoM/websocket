import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

actual fun <T> block(body: suspend CoroutineScope.() -> T) {
    return runBlocking {
        body()
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
