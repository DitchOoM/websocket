plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    signing
}

apply(from = "../gradle/setup.gradle.kts")

group = "com.ditchoom"
val isRunningOnGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"

@Suppress("UNCHECKED_CAST")
val getNextVersion = project.extra["getNextVersion"] as (Boolean) -> Any
project.version = getNextVersion(!isRunningOnGithub).toString()

repositories {
    mavenLocal()
    mavenCentral()
}

val hostOs = org.jetbrains.kotlin.konan.target.HostManager.host

kotlin {
    jvmToolchain(21)

    if (hostOs.family.isAppleFamily) {
        macosX64()
        macosArm64()
        iosArm64()
        iosSimulatorArm64()
        iosX64()
        tvosArm64()
        tvosSimulatorArm64()
        tvosX64()
        // watchOS intentionally excluded: watchosArm64 uses ILP32 ABI so
        // NSInteger/NSUInteger differ in width from LP64 (iOS/macOS/tvOS), and
        // NSURLSessionWebSocketDelegate overrides require bit-width-variant
        // types that can't be reconciled in the shared appleMain metadata.
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        if (hostOs.family.isAppleFamily) {
            appleMain.dependencies {
                // buffer/buffer-codec/buffer-flow come transitively as `api` from the root module
                // (its public API returns Codec/Connection/buffer types), so no explicit adds here.
                api(project(":"))
                implementation(libs.kotlinx.coroutines.core)
            }
            appleTest.dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// --- Publishing ---
// Full POM per module (name/description/url/license/scm/developers), matching socket/buffer — Maven
// Central rejects deployments missing any of these. Module-specific keys (artifactName/libraryName/
// libraryDescription) come from this module's gradle.properties; shared keys (siteUrl/gitUrl/license*/
// developer*) are inherited from the root gradle.properties via `by project`.

val publishedGroupId: String by project
val libraryName: String by project
val artifactName: String by project
val libraryDescription: String by project
val siteUrl: String by project
val gitUrl: String by project
val licenseName: String by project
val licenseUrl: String by project
val developerOrg: String by project
val developerName: String by project
val developerEmail: String by project
val developerId: String by project

project.group = publishedGroupId

val signingInMemoryKey = project.findProperty("signingInMemoryKey")
val signingInMemoryKeyPassword = project.findProperty("signingInMemoryKeyPassword")
val shouldSignAndPublish = isMainBranchGithub && signingInMemoryKey is String && signingInMemoryKeyPassword is String

if (shouldSignAndPublish) {
    signing {
        useInMemoryPgpKeys(signingInMemoryKey as String, signingInMemoryKeyPassword as String)
        sign(publishing.publications)
    }
}

mavenPublishing {
    if (shouldSignAndPublish) {
        publishToMavenCentral()
        signAllPublications()
    }

    coordinates(publishedGroupId, artifactName, project.version.toString())

    pom {
        name.set(libraryName)
        description.set(libraryDescription)
        url.set(siteUrl)
        licenses {
            license {
                name.set(licenseName)
                url.set(licenseUrl)
            }
        }
        developers {
            developer {
                id.set(developerId)
                name.set(developerName)
                email.set(developerEmail)
            }
        }
        organization {
            name.set(developerOrg)
        }
        scm {
            connection.set(gitUrl)
            developerConnection.set(gitUrl)
            url.set(siteUrl)
        }
    }
}
