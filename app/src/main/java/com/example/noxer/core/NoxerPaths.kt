package com.example.noxer.core

import android.content.Context
import java.io.File

/**
 * Canonical filesystem layout for NOXER LAUNCHER.
 * Keeps shared Mojang assets, libraries, version metadata, and runtimes separate
 * from per-installation isolated game directories.
 */
class NoxerPaths(context: Context) {
    val rootDir: File = File(context.filesDir, "noxer").apply { mkdirs() }
    val sharedGameDataDir: File = File(rootDir, "shared_minecraft").apply { mkdirs() }
    val versionsDir: File = File(sharedGameDataDir, "versions").apply { mkdirs() }
    val librariesDir: File = File(sharedGameDataDir, "libraries").apply { mkdirs() }
    val assetsDir: File = File(sharedGameDataDir, "assets").apply { mkdirs() }
    val assetIndexesDir: File = File(assetsDir, "indexes").apply { mkdirs() }
    val assetObjectsDir: File = File(assetsDir, "objects").apply { mkdirs() }
    val loggingDir: File = File(sharedGameDataDir, "logging").apply { mkdirs() }
    val manifestCacheFile: File = File(rootDir, "version_manifest_v2.json")

    val runtimesDir: File = File(rootDir, "runtimes").apply { mkdirs() }
    val instancesDir: File = File(rootDir, "instances").apply { mkdirs() }
    val backupsDir: File = File(rootDir, "backups").apply { mkdirs() }
    val launcherLogsDir: File = File(rootDir, "logs").apply { mkdirs() }
    val crashReportsDir: File = File(rootDir, "crash-reports").apply { mkdirs() }

    fun versionDir(versionId: String): File = File(versionsDir, sanitizeSegment(versionId))
    fun versionJsonFile(versionId: String): File = File(versionDir(versionId), "${sanitizeSegment(versionId)}.json")
    fun versionJarFile(versionId: String): File = File(versionDir(versionId), "${sanitizeSegment(versionId)}.jar")

    fun assetIndexFile(assetIndexId: String): File = File(assetIndexesDir, "${sanitizeSegment(assetIndexId)}.json")
    fun assetObjectFile(hash: String): File {
        val prefix = hash.take(2)
        return File(File(assetObjectsDir, prefix), hash)
    }

    fun libraryFile(relativePath: String): File = File(librariesDir, relativePath)

    fun instanceRoot(instanceId: String): File = File(instancesDir, sanitizeSegment(instanceId)).apply { mkdirs() }
    fun instanceGameDir(instanceId: String): File = File(instanceRoot(instanceId), ".minecraft").apply {
        mkdirs()
        listOf("mods", "saves", "resourcepacks", "shaderpacks", "screenshots", "logs", "crash-reports", "config", "natives")
            .forEach { File(this, it).mkdirs() }
    }

    fun runtimeDir(runtimeId: String): File = File(runtimesDir, sanitizeSegment(runtimeId))

    companion object {
        fun sanitizeSegment(raw: String): String =
            raw.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}
