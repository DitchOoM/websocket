
import com.ditchoom.socket.isNodeJs

actual fun agentName(): String =
    if (isNodeJs) {
        "NodeJS"
    } else {
        "BrowserJS"
    }
