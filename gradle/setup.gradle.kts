@file:Suppress("unused")

import groovy.util.Node
import groovy.xml.XmlParser
import java.net.URL

class Version(
    val major: UInt,
    val minor: UInt,
    val patch: UInt,
    val snapshot: Boolean,
) {
    constructor(string: String, snapshot: Boolean) :
        this(
            string.split('.')[0].toUInt(),
            string.split('.')[1].toUInt(),
            string.split('.')[2].toUInt(),
            snapshot,
        )

    fun incrementMajor() = Version(major + 1u, 0u, 0u, snapshot)

    fun incrementMinor() = Version(major, minor + 1u, 0u, snapshot)

    fun incrementPatch() = Version(major, minor, patch + 1u, snapshot)

    fun snapshot() = Version(major, minor, patch, true)

    fun isVersionZero() = major == 0u && minor == 0u && patch == 0u

    override fun toString(): String =
        if (snapshot) {
            "$major.$minor.$patch-SNAPSHOT"
        } else {
            "$major.$minor.$patch"
        }
}

private var latestVersion: Version? = Version(0u, 0u, 0u, true)

@Suppress("UNCHECKED_CAST")
fun getLatestVersion(): Version {
    val latestVersion = latestVersion
    if (latestVersion != null && !latestVersion.isVersionZero()) {
        return latestVersion
    }
    // Resolve the base version ONCE per build and share it via rootProject.extra: this script is
    // applied per module (own classloader, own state), so without sharing, every module fetches
    // Central's maven-metadata.xml independently at configuration time. While a just-published
    // release propagates through Central's CDN those fetches can disagree, handing modules
    // different bases — observed as the POM pre-flight's "VERSION DRIFT across websocket modules:
    // 2.0.1 2.0.2". Stored as a String because each script classloader has its own Version class.
    val sharedKey = "ditchoomResolvedBaseVersion"
    if (rootProject.extra.has(sharedKey)) {
        val shared = Version(rootProject.extra[sharedKey] as String, false)
        this.latestVersion = shared
        return shared
    }
    val result =
        try {
            // Always resolve the base version from the ROOT artifact so every module (root + submodules)
            // converges on one version lineage — matches socket/buffer. Querying a per-module artifact made
            // never-published submodules (websocket-tcp) 404 → start their own 0.0.0→1.0.0 lineage.
            val xml = URL("https://repo1.maven.org/maven2/com/ditchoom/${rootProject.name}/maven-metadata.xml").readText()
            val versioning = XmlParser().parseText(xml)["versioning"] as List<Node>
            val latestStringList = versioning.first()["latest"] as List<Node>
            Version((latestStringList.first().value() as List<*>).first().toString(), false)
        } catch (_: Exception) {
            Version(0u, 0u, 0u, false)
        }
    // Share even the fetch-failed fallback: a mixed success/failure split across modules is still drift.
    rootProject.extra.set(sharedKey, "${result.major}.${result.minor}.${result.patch}")
    this.latestVersion = result
    return result
}

fun getNextVersion(snapshot: Boolean): Version {
    var v = getLatestVersion()
    if (snapshot) {
        v = v.snapshot()
    }
    if (project.hasProperty("incrementMajor") && project.property("incrementMajor") == "true") {
        return v.incrementMajor()
    } else if (project.hasProperty("incrementMinor") && project.property("incrementMinor") == "true") {
        return v.incrementMinor()
    }
    return v.incrementPatch()
}

project.extra.set("getNextVersion", this::getNextVersion)
