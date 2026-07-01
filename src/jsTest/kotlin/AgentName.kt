
import com.ditchoom.websocket.isNodeJs

actual fun agentName(): String =
    if (isNodeJs) {
        "NodeJS"
    } else {
        "BrowserJS"
    }

actual fun autobahnHost(): String =
    if (isNodeJs) {
        js("process.env.AUTOBAHN_HOST || 'localhost'").unsafeCast<String>()
    } else {
        "localhost"
    }
