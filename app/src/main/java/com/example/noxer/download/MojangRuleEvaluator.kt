package com.example.noxer.download

import com.example.noxer.core.NoxerPaths
import com.example.noxer.version.AssetIndexJson
import com.example.noxer.version.DownloadArtifact
import com.example.noxer.version.LibraryEntry
import com.example.noxer.version.RuleEntry
import com.example.noxer.version.VersionDetails
import java.io.File

data class DownloadTask(
    val id: String,
    val label: String,
    val url: String,
    val destination: File,
    val expectedSha1: String?,
    val expectedSize: Long,
    val category: DownloadCategory,
)

enum class DownloadCategory {
    CLIENT_JAR,
    LIBRARY,
    NATIVE_LIBRARY,
    ASSET_INDEX,
    ASSET_OBJECT,
    LOGGING_CONFIG,
    MOD_LOADER,
    RUNTIME,
}

object MojangRuleEvaluator {

    /**
     * Evaluates Mojang rules for an Android Linux host environment.
     * Minecraft Java desktop manifests target "linux", "windows", or "osx".
     * On Android (Linux bionic + ARM64/x86_64), "linux" rules match while Windows/macOS rules are excluded.
     */
    fun isAllowed(
        rules: List<RuleEntry>?,
        enabledFeatures: Set<String> = emptySet(),
        hostArch: String = "arm64",
    ): Boolean {
        if (rules.isNullOrEmpty()) return true
        var allowed = false
        for (rule in rules) {
            val matchesOs = rule.os?.let { os ->
                val nameMatches = os.name == null || os.name == "linux"
                val archMatches = os.arch == null || os.arch == hostArch || (os.arch == "x86" && hostArch == "x86")
                nameMatches && archMatches
            } ?: true

            val matchesFeatures = rule.features?.all { (feature, required) ->
                enabledFeatures.contains(feature) == required
            } ?: true

            if (matchesOs && matchesFeatures) {
                allowed = rule.action == "allow"
            }
        }
        return allowed
    }

    /**
     * Converts Maven coordinates "group:artifact:version[:classifier][@ext]" to relative repository path.
     */
    fun mavenCoordinateToPath(coordinate: String): String {
        val partsExt = coordinate.split("@")
        val ext = if (partsExt.size > 1) partsExt[1] else "jar"
        val segments = partsExt[0].split(":")
        require(segments.size >= 3) { "Invalid Maven coordinate: $coordinate" }
        val groupPath = segments[0].replace('.', '/')
        val artifact = segments[1]
        val version = segments[2]
        val classifier = if (segments.size >= 4 && segments[3].isNotBlank()) "-${segments[3]}" else ""
        return "$groupPath/$artifact/$version/$artifact-$version$classifier.$ext"
    }

    fun collectDownloadTasks(
        details: VersionDetails,
        paths: NoxerPaths,
        assetIndex: AssetIndexJson? = null,
        hostArch: String = "arm64",
    ): List<DownloadTask> {
        val tasks = mutableListOf<DownloadTask>()

        // 1. Client JAR
        details.downloads?.client?.let { client ->
            tasks += DownloadTask(
                id = "client:${details.id}",
                label = "${details.id}.jar",
                url = client.url,
                destination = paths.versionJarFile(details.id),
                expectedSha1 = client.sha1,
                expectedSize = client.size,
                category = DownloadCategory.CLIENT_JAR,
            )
        }

        // 2. Logging configuration
        details.logging?.client?.file?.let { logFile ->
            tasks += DownloadTask(
                id = "logging:${logFile.id}",
                label = logFile.id,
                url = logFile.url,
                destination = File(paths.loggingDir, logFile.id),
                expectedSha1 = logFile.sha1,
                expectedSize = logFile.size,
                category = DownloadCategory.LOGGING_CONFIG,
            )
        }

        // 3. Libraries & Linux natives
        for (lib in details.libraries) {
            if (!isAllowed(lib.rules, hostArch = hostArch)) continue
            resolveLibraryArtifact(lib, paths)?.let { tasks += it }
            resolveNativeArtifact(lib, paths)?.let { tasks += it }
        }

        // 4. Asset Index
        details.assetIndex?.let { indexInfo ->
            tasks += DownloadTask(
                id = "asset-index:${indexInfo.id}",
                label = "assets/${indexInfo.id}.json",
                url = indexInfo.url,
                destination = paths.assetIndexFile(indexInfo.id),
                expectedSha1 = indexInfo.sha1,
                expectedSize = indexInfo.size,
                category = DownloadCategory.ASSET_INDEX,
            )
        }

        // 5. Asset Objects
        assetIndex?.objects?.forEach { (logicalName, obj) ->
            val prefix = obj.hash.take(2)
            tasks += DownloadTask(
                id = "asset:${obj.hash}",
                label = logicalName,
                url = "https://resources.download.minecraft.net/$prefix/${obj.hash}",
                destination = paths.assetObjectFile(obj.hash),
                expectedSha1 = obj.hash,
                expectedSize = obj.size,
                category = DownloadCategory.ASSET_OBJECT,
            )
        }

        return tasks.distinctBy { it.destination.absolutePath }
    }

    fun resolveLibraryArtifact(lib: LibraryEntry, paths: NoxerPaths): DownloadTask? {
        val artifact = lib.downloads?.artifact
        if (artifact != null) {
            val relPath = artifact.path ?: mavenCoordinateToPath(lib.name)
            return DownloadTask(
                id = "lib:${lib.name}",
                label = lib.name,
                url = artifact.url,
                destination = paths.libraryFile(relPath),
                expectedSha1 = artifact.sha1,
                expectedSize = artifact.size,
                category = DownloadCategory.LIBRARY,
            )
        }
        val baseUrl = lib.url
        if (!baseUrl.isNullOrBlank()) {
            val relPath = mavenCoordinateToPath(lib.name)
            val normalizedBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            return DownloadTask(
                id = "lib:${lib.name}",
                label = lib.name,
                url = "$normalizedBase$relPath",
                destination = paths.libraryFile(relPath),
                expectedSha1 = null,
                expectedSize = 0L,
                category = DownloadCategory.LIBRARY,
            )
        }
        return null
    }

    private fun resolveNativeArtifact(lib: LibraryEntry, paths: NoxerPaths): DownloadTask? {
        val nativeClassifierKey = lib.natives?.get("linux") ?: return null
        val resolvedKey = nativeClassifierKey.replace("\${arch}", "64")
        val classifierArtifact: DownloadArtifact = lib.downloads?.classifiers?.get(resolvedKey) ?: return null
        val relPath = classifierArtifact.path ?: mavenCoordinateToPath("${lib.name}:$resolvedKey")
        return DownloadTask(
            id = "native:${lib.name}:$resolvedKey",
            label = "${lib.name} ($resolvedKey)",
            url = classifierArtifact.url,
            destination = paths.libraryFile(relPath),
            expectedSha1 = classifierArtifact.sha1,
            expectedSize = classifierArtifact.size,
            category = DownloadCategory.NATIVE_LIBRARY,
        )
    }

    fun collectClasspathFiles(details: VersionDetails, paths: NoxerPaths, hostArch: String = "arm64"): List<File> {
        val files = mutableListOf<File>()
        for (lib in details.libraries) {
            if (!isAllowed(lib.rules, hostArch = hostArch)) continue
            val artifact = lib.downloads?.artifact
            val relPath = artifact?.path ?: mavenCoordinateToPath(lib.name)
            val f = paths.libraryFile(relPath)
            if (f.exists()) files += f
        }
        val jar = paths.versionJarFile(details.id)
        if (jar.exists()) files += jar
        return files.distinctBy { it.absolutePath }
    }
}
