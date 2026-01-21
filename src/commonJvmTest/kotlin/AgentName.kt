actual fun agentName(): String =
    try {
        Class.forName("android.os.Build")
        "Android"
    } catch (e: ClassNotFoundException) {
        "JVM"
    }
