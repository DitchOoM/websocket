actual fun agentName(): String {
    return try {
        Class.forName("android.os.Build")
        "Android"
    } catch (e: ClassNotFoundException) {
        "JVM"
    }
}
