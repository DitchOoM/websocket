actual fun agentName(): String =
    try {
        Class.forName("android.os.Build")
        "Android"
    } catch (e: ClassNotFoundException) {
        "JVM"
    }

actual fun autobahnHost(): String = System.getenv("AUTOBAHN_HOST") ?: "localhost"
