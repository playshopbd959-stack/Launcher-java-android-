package com.example.noxer.version

import com.example.noxer.console.ConsoleLogger
import com.example.noxer.core.NoxerPaths
import com.example.noxer.data.db.CachedVersionDao
import com.example.noxer.data.db.CachedVersionEntity
import com.example.noxer.data.db.InstallationDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class VersionCenterRepository(
    private val api: MojangManifestApi,
    private val cachedVersionDao: CachedVersionDao,
    private val installationDao: InstallationDao,
    private val paths: NoxerPaths,
    private val json: Json,
    private val consoleLogger: ConsoleLogger,
) {
    private val _latestIds = MutableStateFlow(LatestVersions(release = "", snapshot = ""))
    val latestIds: StateFlow<LatestVersions> = _latestIds.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    val versions: Flow<List<VersionSummary>> = cachedVersionDao.observeAll().map { entities ->
        entities.map { entity ->
            VersionSummary(
                id = entity.id,
                type = entity.type,
                url = entity.url,
                time = entity.time,
                releaseTime = entity.releaseTime,
                sha1 = entity.sha1,
                complianceLevel = entity.complianceLevel,
            )
        }
    }

    val installedVersionIds: Flow<Set<String>> = installationDao.observeInstalledVersionIds().map { it.toSet() }

    suspend fun syncManifest(forceRefresh: Boolean = false): Result<VersionManifestV2> = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        _syncError.value = null
        try {
            val existingCount = cachedVersionDao.count()
            if (!forceRefresh && existingCount > 0 && paths.manifestCacheFile.exists()) {
                val cached = json.decodeFromString(VersionManifestV2.serializer(), paths.manifestCacheFile.readText())
                _latestIds.value = cached.latest
            }

            val manifest = api.fetchVersionManifest()
            _latestIds.value = manifest.latest
            paths.manifestCacheFile.parentFile?.mkdirs()
            paths.manifestCacheFile.writeText(json.encodeToString(VersionManifestV2.serializer(), manifest))

            val entities = manifest.versions.mapIndexed { idx, v ->
                CachedVersionEntity(
                    id = v.id,
                    type = v.type,
                    url = v.url,
                    time = v.time,
                    releaseTime = v.releaseTime,
                    sha1 = v.sha1,
                    complianceLevel = v.complianceLevel,
                    manifestIndex = idx,
                )
            }
            cachedVersionDao.replaceAll(entities)
            consoleLogger.info("VersionCenter", "Synced ${manifest.versions.size} official versions (latest=${manifest.latest.release}, snapshot=${manifest.latest.snapshot})")
            Result.success(manifest)
        } catch (t: Throwable) {
            val cachedFile = paths.manifestCacheFile
            if (cachedFile.exists()) {
                runCatching {
                    val cached = json.decodeFromString(VersionManifestV2.serializer(), cachedFile.readText())
                    _latestIds.value = cached.latest
                    if (cachedVersionDao.count() == 0) {
                        val entities = cached.versions.mapIndexed { idx, v ->
                            CachedVersionEntity(v.id, v.type, v.url, v.time, v.releaseTime, v.sha1, v.complianceLevel, idx)
                        }
                        cachedVersionDao.replaceAll(entities)
                    }
                    _syncError.value = "Offline cache active: ${t.message}"
                    consoleLogger.warn("VersionCenter", "Manifest fetch failed (${t.message}); using cached manifest (${cached.versions.size} versions)")
                    return@withContext Result.success(cached)
                }
            }
            _syncError.value = "Failed to fetch Mojang manifest: ${t.message}"
            consoleLogger.error("VersionCenter", "Failed to fetch Mojang manifest: ${t.message}")
            Result.failure(t)
        } finally {
            _isSyncing.value = false
        }
    }

    suspend fun getOrFetchVersionDetails(summary: VersionSummary): Result<VersionDetails> = withContext(Dispatchers.IO) {
        val localFile: File = paths.versionJsonFile(summary.id)
        try {
            if (localFile.exists() && localFile.length() > 0L) {
                val parsed = json.decodeFromString(VersionDetails.serializer(), localFile.readText())
                return@withContext Result.success(parsed)
            }
            val rawJson = api.fetchRawString(summary.url)
            val parsed = json.decodeFromString(VersionDetails.serializer(), rawJson)
            localFile.parentFile?.mkdirs()
            localFile.writeText(rawJson)
            Result.success(parsed)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    companion object {
        /**
         * Official Java requirement from VersionDetails.javaVersion.majorVersion,
         * or accurate Mojang fallback when older JSONs (pre-1.17) omit javaVersion.
         */
        fun inferRequiredJavaMajor(versionId: String, details: VersionDetails?): Int {
            details?.javaVersion?.majorVersion?.let { return it }
            val parts = versionId.split(".", "-")
            val major = parts.getOrNull(0)?.toIntOrNull()
            val minor = parts.getOrNull(1)?.toIntOrNull()
            if (major != null && major >= 26) {
                return 25
            }
            if (major == 1 && minor != null) {
                return when {
                    minor >= 21 -> 21
                    minor >= 18 -> 17
                    minor == 17 -> 16
                    else -> 8
                }
            }
            return when {
                versionId.startsWith("26w") || versionId.startsWith("26.") -> 25
                versionId.startsWith("24w") || versionId.startsWith("25w") -> 21
                else -> 8
            }
        }

        fun matchesCategory(
            version: VersionSummary,
            category: VersionCategory,
            latestReleaseId: String,
            installedIds: Set<String>,
        ): Boolean = when (category) {
            VersionCategory.LATEST_RELEASE -> version.id == latestReleaseId
            VersionCategory.ALL_RELEASES -> version.type == "release"
            VersionCategory.SNAPSHOTS -> version.type == "snapshot"
            VersionCategory.OLD_RELEASES -> version.type == "old_beta" || version.type == "old_alpha"
            VersionCategory.HISTORICAL -> {
                version.type == "old_alpha" || version.type == "old_beta" ||
                    version.id.startsWith("1.0") || version.id.startsWith("1.1") ||
                    version.id.startsWith("1.2") || version.id.startsWith("1.3") ||
                    version.id.startsWith("1.4") || version.id.startsWith("1.5") ||
                    version.id.startsWith("1.6") || version.id.startsWith("1.7") ||
                    version.id.startsWith("1.8")
            }
            VersionCategory.EXPERIMENTAL -> {
                version.type == "snapshot" && (
                    version.id.contains("rc", ignoreCase = true) ||
                        version.id.contains("pre", ignoreCase = true) ||
                        version.id.contains("exp", ignoreCase = true)
                    )
            }
            VersionCategory.INSTALLED -> installedIds.contains(version.id)
        }
    }
}
