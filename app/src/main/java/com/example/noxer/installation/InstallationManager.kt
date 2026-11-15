package com.example.noxer.installation

import com.example.noxer.console.ConsoleLogger
import com.example.noxer.core.NoxerPaths
import com.example.noxer.data.db.InstallationDao
import com.example.noxer.data.db.InstallationEntity
import com.example.noxer.data.db.InstallationStatus
import com.example.noxer.data.db.LoaderType
import com.example.noxer.download.DownloadEngine
import com.example.noxer.download.MojangRuleEvaluator
import com.example.noxer.mods.ModLoaderManager
import com.example.noxer.version.VersionCenterRepository
import com.example.noxer.version.VersionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

data class CreateInstallationRequest(
    val name: String,
    val version: VersionSummary,
    val loaderType: LoaderType,
    val loaderVersion: String?,
    val javaRuntimeId: String,
    val customDirectoryName: String?,
    val memoryMb: Int,
    val jvmArgs: String,
    val resolutionWidth: Int,
    val resolutionHeight: Int,
    val resolutionScalePercent: Int,
    val fullscreen: Boolean,
    val rendererId: String,
    val controlProfileId: String,
    val allowOverwriteExistingName: Boolean = false,
)

class InstallationManager(
    private val installationDao: InstallationDao,
    private val versionRepository: VersionCenterRepository,
    private val downloadEngine: DownloadEngine,
    private val modLoaderManager: ModLoaderManager,
    private val paths: NoxerPaths,
    private val consoleLogger: ConsoleLogger,
) {
    val installations: Flow<List<InstallationEntity>> = installationDao.observeAll()

    suspend fun checkNameExists(name: String): InstallationEntity? =
        installationDao.getByName(name.trim())

    suspend fun createAndInstall(request: CreateInstallationRequest): Result<InstallationEntity> =
        withContext(Dispatchers.IO) {
            val trimmedName = request.name.trim()
            if (trimmedName.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Installation name cannot be empty."))
            }

            val existing = installationDao.getByName(trimmedName)
            if (existing != null && !request.allowOverwriteExistingName) {
                return@withContext Result.failure(
                    IllegalStateException("DUPLICATE_NAME:An installation named '$trimmedName' already exists.")
                )
            }

            val instanceId = existing?.id ?: UUID.randomUUID().toString().take(8)
            val dirSegment = request.customDirectoryName?.takeIf { it.isNotBlank() }
                ?.let { NoxerPaths.sanitizeSegment(it) }
                ?: "${NoxerPaths.sanitizeSegment(trimmedName)}_$instanceId"
            val gameDir = File(File(paths.instancesDir, dirSegment), ".minecraft").apply {
                mkdirs()
                listOf("mods", "saves", "resourcepacks", "shaderpacks", "screenshots", "logs", "crash-reports", "config", "natives")
                    .forEach { File(this, it).mkdirs() }
            }

            val initialEntity = InstallationEntity(
                id = instanceId,
                name = trimmedName,
                minecraftVersionId = request.version.id,
                loaderType = request.loaderType.name,
                loaderVersion = request.loaderVersion,
                javaRuntimeId = request.javaRuntimeId,
                gameDirPath = gameDir.absolutePath,
                memoryMb = request.memoryMb,
                jvmArgs = request.jvmArgs,
                resolutionWidth = request.resolutionWidth,
                resolutionHeight = request.resolutionHeight,
                resolutionScalePercent = request.resolutionScalePercent,
                fullscreen = request.fullscreen,
                rendererId = request.rendererId,
                controlProfileId = request.controlProfileId,
                status = InstallationStatus.DOWNLOADING.name,
            )
            installationDao.upsert(initialEntity)
            consoleLogger.info("Installer", "Creating installation '$trimmedName' (${request.version.id} / ${request.loaderType.displayName})")

            installOrRepairInternal(initialEntity, request.version, downloadConcurrency = 6)
        }

    suspend fun updateConfiguration(updated: InstallationEntity): Result<InstallationEntity> =
        withContext(Dispatchers.IO) {
            installationDao.upsert(updated)
            consoleLogger.info("Installer", "Updated configuration for '${updated.name}'")
            Result.success(updated)
        }

    suspend fun duplicateInstallation(source: InstallationEntity): Result<InstallationEntity> =
        withContext(Dispatchers.IO) {
            try {
                val newId = UUID.randomUUID().toString().take(8)
                var copyName = "${source.name} (Copy)"
                var suffix = 2
                while (installationDao.getByName(copyName) != null) {
                    copyName = "${source.name} (Copy $suffix)"
                    suffix++
                }
                val newDir = paths.instanceGameDir("${NoxerPaths.sanitizeSegment(copyName)}_$newId")
                val srcDir = File(source.gameDirPath)
                if (srcDir.exists()) {
                    srcDir.copyRecursively(newDir, overwrite = true)
                }

                val copy = source.copy(
                    id = newId,
                    name = copyName,
                    gameDirPath = newDir.absolutePath,
                    createdAt = System.currentTimeMillis(),
                    lastPlayedAt = null,
                )
                installationDao.upsert(copy)
                consoleLogger.info("Installer", "Duplicated '${source.name}' -> '$copyName'")
                Result.success(copy)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    suspend fun repairInstallation(
        entity: InstallationEntity,
        versionSummary: VersionSummary,
        concurrency: Int = 6,
    ): Result<InstallationEntity> = withContext(Dispatchers.IO) {
        installationDao.upsert(entity.copy(status = InstallationStatus.VERIFYING.name, lastError = null))
        installOrRepairInternal(entity, versionSummary, concurrency)
    }

    suspend fun deleteInstallation(entity: InstallationEntity) = withContext(Dispatchers.IO) {
        val gameDir = File(entity.gameDirPath)
        val instanceRoot = gameDir.parentFile ?: gameDir
        if (instanceRoot.exists() && instanceRoot.canonicalPath.startsWith(paths.instancesDir.canonicalPath)) {
            instanceRoot.deleteRecursively()
        }
        installationDao.deleteById(entity.id)
        consoleLogger.info("Installer", "Deleted installation '${entity.name}'")
    }

    private suspend fun installOrRepairInternal(
        entity: InstallationEntity,
        versionSummary: VersionSummary,
        downloadConcurrency: Int,
    ): Result<InstallationEntity> {
        try {
            val detailsResult = versionRepository.getOrFetchVersionDetails(versionSummary)
            if (detailsResult.isFailure) {
                val err = detailsResult.exceptionOrNull()?.message ?: "Failed to fetch version metadata"
                val failed = entity.copy(status = InstallationStatus.ERROR.name, lastError = err)
                installationDao.upsert(failed)
                return Result.failure(IOException(err))
            }
            val details = detailsResult.getOrThrow()

            // Fetch Asset Index JSON first so we can verify/download all asset objects
            val assetIndexJson = details.assetIndex?.let { idx ->
                val idxFile = paths.assetIndexFile(idx.id)
                if (idxFile.exists() && DownloadEngine.verifyFileSha1AndSize(idxFile, idx.sha1, idx.size)) {
                    SHARED_JSON.decodeFromString(com.example.noxer.version.AssetIndexJson.serializer(), idxFile.readText())
                } else null
            }

            // Ensure asset index is downloaded if missing
            val baseTasks = MojangRuleEvaluator.collectDownloadTasks(details, paths, assetIndexJson)
            val indexTask = baseTasks.firstOrNull { it.category == com.example.noxer.download.DownloadCategory.ASSET_INDEX }
            val resolvedAssetIndex = if (assetIndexJson != null) {
                assetIndexJson
            } else if (indexTask != null) {
                val idxDl = downloadEngine.downloadAll("Fetching asset index ${details.assetIndex?.id}", listOf(indexTask), concurrency = 1)
                if (idxDl.isFailure) {
                    val err = idxDl.exceptionOrNull()?.message ?: "Asset index download failed"
                    installationDao.upsert(entity.copy(status = InstallationStatus.ERROR.name, lastError = err))
                    return Result.failure(IOException(err))
                }
                SHARED_JSON.decodeFromString(com.example.noxer.version.AssetIndexJson.serializer(), indexTask.destination.readText())
            } else null

            val allTasks = MojangRuleEvaluator.collectDownloadTasks(details, paths, resolvedAssetIndex)
            val integrity = downloadEngine.verifyIntegrity(allTasks)
            val missingOrBad = integrity.missingTasks + integrity.corruptedTasks

            consoleLogger.info(
                "Installer",
                "${entity.name}: ${integrity.validCount}/${integrity.totalChecked} files valid, ${missingOrBad.size} to download",
            )

            if (missingOrBad.isNotEmpty()) {
                val dlResult = downloadEngine.downloadAll(
                    phaseLabel = "Installing ${details.id}",
                    tasks = missingOrBad,
                    concurrency = downloadConcurrency,
                )
                if (dlResult.isFailure) {
                    val err = dlResult.exceptionOrNull()?.message ?: "Download failed"
                    val status = if (err.contains("cancelled", ignoreCase = true)) {
                        InstallationStatus.NOT_INSTALLED.name
                    } else {
                        InstallationStatus.CORRUPTED.name
                    }
                    val failed = entity.copy(
                        status = status,
                        lastError = err,
                        verifiedFileCount = integrity.validCount,
                        totalFileCount = allTasks.size,
                    )
                    installationDao.upsert(failed)
                    return Result.failure(dlResult.exceptionOrNull()!!)
                }
            }

            // Mod loader installation (Fabric real profile + libraries)
            val loaderType = runCatching { LoaderType.valueOf(entity.loaderType) }.getOrDefault(LoaderType.VANILLA)
            if (loaderType == LoaderType.FABRIC && !entity.loaderVersion.isNullOrBlank()) {
                val fabricRes = modLoaderManager.installFabricProfile(entity.id, details.id, entity.loaderVersion)
                if (fabricRes.isFailure) {
                    val err = fabricRes.exceptionOrNull()?.message ?: "Fabric install failed"
                    installationDao.upsert(entity.copy(status = InstallationStatus.ERROR.name, lastError = err))
                    return Result.failure(fabricRes.exceptionOrNull()!!)
                }
            }

            val postCheck = downloadEngine.verifyIntegrity(allTasks)
            val ready = entity.copy(
                status = if (postCheck.isHealthy) InstallationStatus.READY.name else InstallationStatus.CORRUPTED.name,
                lastError = if (postCheck.isHealthy) null else "${postCheck.missingTasks.size + postCheck.corruptedTasks.size} files failed verification",
                verifiedFileCount = postCheck.validCount,
                totalFileCount = postCheck.totalChecked,
            )
            installationDao.upsert(ready)
            consoleLogger.info("Installer", "'${ready.name}' verified: ${ready.verifiedFileCount}/${ready.totalFileCount} files SHA-1 OK")
            return Result.success(ready)
        } catch (t: Throwable) {
            val failed = entity.copy(status = InstallationStatus.ERROR.name, lastError = t.message)
            installationDao.upsert(failed)
            return Result.failure(t)
        }
    }

    companion object {
        private val SHARED_JSON = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
