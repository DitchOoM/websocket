pluginManagement {
    repositories {
        mavenLocal()
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.namespace == "com.android" || requested.id.name == "kotlin-android-extensions") {
                useModule("com.android.tools.build:gradle:8.1.0")
            }
        }
    }
}
rootProject.name = "websocket"
plugins {
    id("com.gradle.enterprise") version ("3.15")
}
gradleEnterprise {
    buildScan {
        termsOfServiceUrl = "https://gradle.com/terms-of-service"
        termsOfServiceAgree = "yes"
        publishOnFailureIf(!System.getenv("CI").isNullOrEmpty())
    }
}
