package com.example.noxer.runtime

import android.os.StatFs
import com.example.noxer.console.ConsoleLogger
import com.example.noxer.core.NoxerPaths
import com.example.noxer.data.db.InstallationDao
import com.example.noxer.data.db.RuntimeDao
import com.example.noxer.data.db.RuntimeEntity
import com.example.noxer.download.DownloadEngine
import com.example.noxer.nativebridge.NoxerNativeBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Real Android-compatible Java Runtime Engine for NOXER LAUNCHER.
 * Handles architecture/ABI detection, Android Bionic OpenJDK resolution (Java 8, 17, 21, 25+),
 * resumable chunked downloads with SHA-256 verification, safe archive extraction, permission setup,
 * real execution verification before registration, runtime coexistence, updates, and cleanup.
 */
class JavaRuntimeManager(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val runtimeDao: RuntimeDao,
    private val paths: NoxerPaths,
    private val consoleLogger: ConsoleLogger,
    private val installationDao: InstallationDao? = null,
    private val sourceResolver: RuntimeSourceResolver = AndroidRuntimeSourceResolver(okHttpClient, json),
    private val executionProbe: RuntimeExecutionProbe = RuntimeExecutionProbe { libJvmPath, _ ->
        NoxerNativeBridge.probeLibJvm(libJvmPath)
    },
    private val availableBytesProvider: () -> Long = {
        runCatching {
            paths.runtimesDir.mkdirs()
            val stat = StatFs(paths.runtimesDir.absolutePath)
            if (stat.blockCountLong == 0L && stat.availableBytes == 0L) Long.MAX_VALUE else stat.availableBytes
        }.getOrDefault(Long.MAX_VALUE)
    },
) {
    val runtimes: Flow<List<RuntimeEntity>> = runtimeDao.observeAll()

    private val verifier = AndroidRuntimeVerifier(executionProbe)
    private val installMutex = Mutex()

    @Volatile
    private var cancelRequested = false

    private val _installProgress = MutableStateFlow(RuntimeInstallProgress())
    val installProgress: StateFlow<RuntimeInstallProgress> = _installProgress.asStateFlow()

    fun detectAbiInfo(): AndroidAbiInfo = sourceResolver.detectAbiInfo()

    fun getHostDiagnostics(): RuntimeDiagnostics {
        val abi = detectAbiInfo()
        return RuntimeDiagnostics(
            hostAbis = abi.supportedAbis.ifEmpty { listOf(abi.primaryAbi) },
            primaryAbi = abi.primaryAbi,
            isArm64Host = abi.isArm64Compatible,
            nativeBridgeLoaded = NoxerNativeBridge.isNativeLibraryLoaded(),
            eglReport = NoxerNativeBridge.probeEglCapabilities(),
        )
    }

    fun cancelInstall() {
        cancelRequested = true
        _installProgress.value = _installProgress.value.copy(
            phase = "Cancelling",
            statusText = "Cancelling Java runtime installation...",
        )
    }

    suspend fun findCompatibleVerifiedRuntime(
        majorVersion: Int,
        preferredRuntimeId: String = "auto",
    ): RuntimeEntity? = withContext(Dispatchers.IO) {
        val abi = detectAbiInfo()

        suspend fun checkCandidate(candidate: RuntimeEntity?): RuntimeEntity? {
            if (candidate == null || !candidate.isInstalled) return null
            if (candidate.majorVersion != majorVersion) return null
            val installDir = File(candidate.installPath)
            if (!installDir.exists()) {
                runtimeDao.upsert(
                    candidate.copy(
                        isInstalled = false,
                        isVerified = false,
                        diagnosticMessage = "Runtime directory missing from disk.",
                    )
                )
                return null
            }
            if (candidate.abi != abi.primaryAbi && !candidate.abi.equals(abi.primaryAbi, ignoreCase = true)) {
                return null
            }
            // Re-verify if DB didn't record verified status or libjvm.so/bin/java moved
            val libJvm = File(candidate.libJvmPath).takeIf { it.exists() } ?: ArchiveExtractor.findLibJvm(installDir)
            if (libJvm == null) {
                runtimeDao.upsert(
                    candidate.copy(
                        isInstalled = false,
                        isVerified = false,
                        diagnosticMessage = "libjvm.so missing inside runtime.",
                    )
                )
                return null
            }
            if (candidate.isVerified && candidate.libJvmPath == libJvm.absolutePath) {
                val elf = AndroidRuntimeVerifier.inspectElf(libJvm)
                if (elf.isValidElf && !elf.hasGlibcDependencies) {
                    return candidate
                }
            }
            val verified = verifyRuntime(candidate)
            return verified.takeIf { it.isInstalled && it.isVerified }
        }

        if (preferredRuntimeId != "auto" && preferredRuntimeId.isNotBlank()) {
            checkCandidate(runtimeDao.getById(preferredRuntimeId))?.let { return@withContext it }
        }

        runtimeDao.getVerifiedByMajorVersion(majorVersion)?.let { candidate ->
            checkCandidate(candidate)?.let { return@withContext it }
        }

        val all = runtimeDao.getAll().filter { it.majorVersion == majorVersion }
        for (rt in all) {
            checkCandidate(rt)?.let { return@withContext it }
        }
        null
    }

    suspend fun ensureRuntimeInstalledAndVerified(
        majorVersion: Int,
        preferredRuntimeId: String = "auto",
        onProgress: ((RuntimeInstallProgress) -> Unit)? = null,
    ): Result<RuntimeEntity> {
        findCompatibleVerifiedRuntime(majorVersion, preferredRuntimeId)?.let { existing ->
            return Result.success(existing)
        }
        return fetchAndInstallRuntime(majorVersion = majorVersion, onProgress = onProgress)
    }

    suspend fun fetchAndInstallRuntime(
        majorVersion: Int,
        forceReinstall: Boolean = false,
        onProgress: ((RuntimeInstallProgress) -> Unit)? = null,
    ): Result<RuntimeEntity> = withContext(Dispatchers.IO) {
        installMutex.withLock {
            cancelRequested = false

            fun publish(
                phase: String,
                status: String,
                fraction: Float,
                downloadedBytes: Long = _installProgress.value.downloadedBytes,
                totalBytes: Long = _installProgress.value.totalBytes,
            ) {
                val next = RuntimeInstallProgress(
                    isInstalling = true,
                    majorVersion = majorVersion,
                    phase = phase,
                    statusText = status,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    progressFraction = fraction.coerceIn(0f, 1f),
                )
                _installProgress.value = next
                onProgress?.invoke(next)
            }

            if (!forceReinstall) {
                findCompatibleVerifiedRuntime(majorVersion)?.let { existing ->
                    _installProgress.value = RuntimeInstallProgress()
                    return@withLock Result.success(existing)
                }
            }

            val abi = detectAbiInfo()
            val runtimeId = "android-openjdk-$majorVersion-${abi.primaryAbi}"
            val stagingDir = File(paths.runtimesDir, "$runtimeId.staging")
            val archiveCacheDir = File(paths.runtimesDir, ".downloads/$runtimeId").apply { mkdirs() }
            val finalDir = paths.runtimeDir(runtimeId)

            publish(
                phase = "Resolving",
                status = "Detecting Java $majorVersion Android (${abi.primaryAbi}) runtime...",
                fraction = 0.04f,
            )
            consoleLogger.info(
                "JavaRuntime",
                "Resolving Java $majorVersion for Android ABI=${abi.primaryAbi} (ARM64 compatible=${abi.isArm64Compatible})",
            )

            try {
                val candidates = sourceResolver.resolvePackages(majorVersion, abi)
                if (candidates.isEmpty()) {
                    throw IOException("No Android-compatible Java $majorVersion runtime found for ${abi.primaryAbi}.")
                }

                var lastError: Throwable? = null
                for ((idx, pkg) in candidates.withIndex()) {
                    if (cancelRequested) throw CancellationException("Runtime installation cancelled")
                    try {
                        val totalExpectedBytes = pkg.sizeBytes + pkg.companionPackages.sumOf { it.sizeBytes }
                        val requiredFreeBytes = if (totalExpectedBytes > 2_000_000L) {
                            maxOf(totalExpectedBytes * 2L + 64L * 1024L * 1024L, 180L * 1024L * 1024L)
                        } else {
                            maxOf(totalExpectedBytes * 2L, 256L * 1024L)
                        }
                        val freeBytes = availableBytesProvider()
                        if (freeBytes < requiredFreeBytes) {
                            throw IOException(
                                "Insufficient storage for Java $majorVersion: need ${requiredFreeBytes / (1024 * 1024)} MB free, only ${freeBytes / (1024 * 1024)} MB available."
                            )
                        }

                        consoleLogger.info(
                            "JavaRuntime",
                            "Downloading candidate ${idx + 1}/${candidates.size}: ${pkg.providerName} (${pkg.fullVersion})",
                        )

                        if (stagingDir.exists()) stagingDir.deleteRecursively()
                        stagingDir.mkdirs()

                        var aggregateDownloaded = 0L
                        val effectiveTotal = totalExpectedBytes.coerceAtLeast(1L)

                        // 1. Download & checksum-verify primary archive (resumable .part)
                        val mainFileName = pkg.downloadUrl.substringAfterLast('/').substringBefore('?')
                            .ifBlank { "$runtimeId.archive" }
                        val mainArchive = File(archiveCacheDir, mainFileName)
                        downloadFileResumable(
                            url = pkg.downloadUrl,
                            dest = mainArchive,
                            expectedSha256 = pkg.sha256,
                            expectedSize = pkg.sizeBytes,
                        ) { readDelta, bytesSoFar, fileTotal ->
                            aggregateDownloaded += readDelta
                            val dlFraction = if (totalExpectedBytes > 0L) {
                                0.08f + 0.56f * (aggregateDownloaded.toFloat() / effectiveTotal.toFloat())
                            } else if (fileTotal > 0L) {
                                0.08f + 0.56f * (bytesSoFar.toFloat() / fileTotal.toFloat())
                            } else {
                                0.35f
                            }
                            val dlMb = bytesSoFar / (1024f * 1024f)
                            val totalMb = (if (fileTotal > 0L) fileTotal else pkg.sizeBytes) / (1024f * 1024f)
                            val sizeLabel = if (totalMb > 0.1f) {
                                String.format("%.1f / %.1f MB", dlMb, totalMb)
                            } else {
                                "${bytesSoFar / 1024} KB"
                            }
                            publish(
                                phase = "Downloading",
                                status = "Downloading Java $majorVersion (${pkg.providerName}) • $sizeLabel",
                                fraction = dlFraction.coerceIn(0.08f, 0.64f),
                                downloadedBytes = aggregateDownloaded,
                                totalBytes = totalExpectedBytes.coerceAtLeast(fileTotal),
                            )
                        }

                        // 2. Download any split binary / native companion packages
                        val companionArchives = mutableListOf<Pair<CompanionRuntimePackage, File>>()
                        for ((cIdx, comp) in pkg.companionPackages.withIndex()) {
                            if (cancelRequested) throw CancellationException("Runtime installation cancelled")
                            val compFile = File(archiveCacheDir, "${comp.packageName}_${cIdx}.${comp.format.name.lowercase()}")
                            downloadFileResumable(
                                url = comp.downloadUrl,
                                dest = compFile,
                                expectedSha256 = comp.sha256,
                                expectedSize = comp.sizeBytes,
                            ) { readDelta, _, _ ->
                                aggregateDownloaded += readDelta
                                val dlFraction = if (totalExpectedBytes > 0L) {
                                    0.08f + 0.56f * (aggregateDownloaded.toFloat() / effectiveTotal.toFloat())
                                } else {
                                    0.60f
                                }
                                publish(
                                    phase = "Downloading",
                                    status = "Downloading companion ${comp.packageName} (${cIdx + 1}/${pkg.companionPackages.size})...",
                                    fraction = dlFraction.coerceIn(0.08f, 0.68f),
                                    downloadedBytes = aggregateDownloaded,
                                    totalBytes = totalExpectedBytes,
                                )
                            }
                            companionArchives += comp to compFile
                        }

                        // 3. Extract archives safely into staging directory
                        publish(
                            phase = "Extracting",
                            status = "Extracting Java $majorVersion (${abi.primaryAbi}) into private storage...",
                            fraction = 0.72f,
                        )
                        ArchiveExtractor.extractArchive(
                            archive = mainArchive,
                            format = pkg.format,
                            destDir = stagingDir,
                            stripSingleRootDir = (pkg.format != RuntimeArchiveFormat.DEB),
                        )

                        for ((comp, compFile) in companionArchives) {
                            if (cancelRequested) throw CancellationException("Runtime installation cancelled")
                            val prefixFilter = if (comp.format == RuntimeArchiveFormat.DEB) listOf("/lib/", ".so") else null
                            ArchiveExtractor.extractArchive(
                                archive = compFile,
                                format = comp.format,
                                destDir = stagingDir,
                                stripSingleRootDir = false,
                                onlyRelativeSubdirPrefixes = prefixFilter,
                            )
                        }

                        publish(
                            phase = "Configuring",
                            status = "Configuring permissions, symlinks & library paths...",
                            fraction = 0.84f,
                        )
                        ArchiveExtractor.normalizeExtractedRuntimeLayout(stagingDir)
                        ArchiveExtractor.configurePermissions(stagingDir)

                        // 4. Real verification BEFORE moving into final installed registry
                        publish(
                            phase = "Verifying",
                            status = "Verifying Java $majorVersion Android compatibility & execution...",
                            fraction = 0.90f,
                        )
                        val verification = verifier.verify(
                            javaHome = stagingDir,
                            expectedAbi = abi.primaryAbi,
                            expectedMajorVersion = majorVersion,
                        )
                        if (!verification.isVerified) {
                            throw IOException("Runtime verification failed: ${verification.diagnosticMessage}")
                        }

                        // 5. Commit atomically into final runtime location & register in Room DB
                        if (finalDir.exists()) finalDir.deleteRecursively()
                        if (!stagingDir.renameTo(finalDir)) {
                            stagingDir.copyRecursively(finalDir, overwrite = true)
                            stagingDir.deleteRecursively()
                        }
                        ArchiveExtractor.configurePermissions(finalDir)

                        val finalLibJvm = ArchiveExtractor.findLibJvm(finalDir)
                            ?: throw IOException("libjvm.so disappeared after moving runtime to ${finalDir.absolutePath}")
                        val finalJavaBin = ArchiveExtractor.findJavaBinary(finalDir)
                        val diskBytes = finalDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        val existing = runtimeDao.getById(runtimeId)
                        val allExisting = runtimeDao.getAll()
                        val shouldBeDefault = existing?.isDefault ?: allExisting.none { it.isDefault }

                        val entity = RuntimeEntity(
                            id = runtimeId,
                            displayName = pkg.displayName,
                            majorVersion = majorVersion,
                            fullVersion = verification.detectedJavaVersion.ifBlank { pkg.fullVersion },
                            abi = abi.primaryAbi,
                            installPath = finalDir.absolutePath,
                            libJvmPath = finalLibJvm.absolutePath,
                            javaBinPath = finalJavaBin?.absolutePath.orEmpty(),
                            sourceProvider = pkg.providerName,
                            isDefault = shouldBeDefault,
                            sha256 = pkg.sha256,
                            sizeBytes = diskBytes.coerceAtLeast(pkg.sizeBytes),
                            downloadUrl = pkg.downloadUrl,
                            isInstalled = true,
                            isVerified = true,
                            diagnosticMessage = verification.diagnosticMessage,
                            installedAt = System.currentTimeMillis(),
                        )
                        runtimeDao.upsert(entity)
                        archiveCacheDir.deleteRecursively()

                        publish(
                            phase = "Ready",
                            status = "Java $majorVersion verified and ready (${abi.primaryAbi})",
                            fraction = 1.0f,
                        )
                        consoleLogger.info(
                            "JavaRuntime",
                            "Installed & verified ${entity.displayName} at ${entity.installPath} via ${verification.executionMethod}",
                        )
                        return@withLock Result.success(entity)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        lastError = t
                        consoleLogger.warn(
                            "JavaRuntime",
                            "Candidate ${pkg.providerName} failed for Java $majorVersion: ${t.message}",
                        )
                        if (stagingDir.exists()) stagingDir.deleteRecursively()
                    }
                }

                throw lastError ?: IOException("All Java $majorVersion Android runtime candidates failed.")
            } catch (t: Throwable) {
                if (stagingDir.exists()) stagingDir.deleteRecursively()
                consoleLogger.error("JavaRuntime", "Failed to install Java $majorVersion: ${t.message}")
                Result.failure(t)
            } finally {
                _installProgress.value = RuntimeInstallProgress()
            }
        }
    }

    suspend fun updateRuntime(
        runtime: RuntimeEntity,
        onProgress: ((RuntimeInstallProgress) -> Unit)? = null,
    ): Result<RuntimeEntity> = fetchAndInstallRuntime(
        majorVersion = runtime.majorVersion,
        forceReinstall = true,
        onProgress = onProgress,
    )

    suspend fun verifyRuntime(runtime: RuntimeEntity): RuntimeEntity = withContext(Dispatchers.IO) {
        val installDir = File(runtime.installPath)
        if (!installDir.exists()) {
            val missing = runtime.copy(
                isInstalled = false,
                isVerified = false,
                diagnosticMessage = "ERROR: Runtime directory missing (${runtime.installPath})",
            )
            runtimeDao.upsert(missing)
            return@withContext missing
        }

        val result = verifier.verify(
            javaHome = installDir,
            expectedAbi = runtime.abi,
            expectedMajorVersion = runtime.majorVersion,
        )
        val updated = runtime.copy(
            fullVersion = result.detectedJavaVersion.ifBlank { runtime.fullVersion },
            libJvmPath = result.libJvmPath.ifBlank { runtime.libJvmPath },
            javaBinPath = result.javaBinPath,
            isInstalled = result.libJvmPath.isNotBlank() && File(result.libJvmPath).exists(),
            isVerified = result.isVerified,
            diagnosticMessage = result.diagnosticMessage,
        )
        runtimeDao.upsert(updated)
        consoleLogger.info("JavaRuntime", "Verify ${runtime.id}: ${updated.diagnosticMessage}")
        updated
    }

    suspend fun setDefaultRuntime(runtime: RuntimeEntity) = withContext(Dispatchers.IO) {
        runtimeDao.setDefaultRuntime(runtime.id)
        consoleLogger.info("JavaRuntime", "Set ${runtime.displayName} as default runtime")
    }

    suspend fun deleteRuntime(runtime: RuntimeEntity) = withContext(Dispatchers.IO) {
        File(runtime.installPath).deleteRecursively()
        runtimeDao.deleteById(runtime.id)
        installationDao?.clearRuntimeReference(runtime.id)
        consoleLogger.info("JavaRuntime", "Removed runtime ${runtime.displayName} (${runtime.id})")
    }

    fun buildRuntimeEnvironment(
        runtime: RuntimeEntity,
        nativesDir: File? = null,
    ): RuntimeEnvironment {
        val javaHome = File(runtime.installPath)
        val libJvm = File(runtime.libJvmPath).takeIf { it.exists() }
            ?: ArchiveExtractor.findLibJvm(javaHome)
            ?: File(javaHome, "lib/server/libjvm.so")
        val javaBin = runtime.javaBinPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }
            ?: ArchiveExtractor.findJavaBinary(javaHome)
        return AndroidRuntimeVerifier.buildRuntimeEnvironment(javaHome, libJvm, javaBin, nativesDir)
    }

    private fun downloadFileResumable(
        url: String,
        dest: File,
        expectedSha256: String,
        expectedSize: Long,
        onBytesDelta: (delta: Long, currentFileBytes: Long, totalFileBytes: Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        if (dest.exists() && isChecksumAndSizeValid(dest, expectedSha256, expectedSize)) {
            onBytesDelta(dest.length(), dest.length(), dest.length())
            return
        }

        val partFile = File(dest.parentFile, "${dest.name}.part")
        var attempt = 0
        var lastErr: Throwable? = null
        while (attempt < 3 && !cancelRequested) {
            attempt++
            try {
                val existingBytes = if (partFile.exists()) partFile.length() else 0L
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "NoxerLauncher/1.0 (Android)")
                if (existingBytes > 0L) {
                    reqBuilder.header("Range", "bytes=$existingBytes-")
                }

                okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                    val appendMode: Boolean
                    var currentCount: Long
                    when (response.code) {
                        206 -> {
                            appendMode = true
                            currentCount = existingBytes
                        }
                        200 -> {
                            appendMode = false
                            currentCount = 0L
                        }
                        416 -> {
                            partFile.delete()
                            throw IOException("HTTP 416 range mismatch")
                        }
                        else -> throw IOException("HTTP ${response.code} downloading $url")
                    }

                    val body = response.body ?: throw IOException("Empty response body for $url")
                    val contentLen = body.contentLength()
                    val resolvedTotal = when {
                        contentLen > 0L && appendMode -> existingBytes + contentLen
                        contentLen > 0L -> contentLen
                        expectedSize > 0L -> expectedSize
                        else -> -1L
                    }

                    body.byteStream().use { input ->
                        FileOutputStream(partFile, appendMode).use { output ->
                            val buf = ByteArray(32 * 1024)
                            var n: Int
                            while (input.read(buf).also { n = it } != -1) {
                                if (cancelRequested) throw CancellationException("Runtime download cancelled")
                                output.write(buf, 0, n)
                                currentCount += n
                                onBytesDelta(n.toLong(), currentCount, resolvedTotal)
                            }
                            output.flush()
                        }
                    }
                }

                if (!isChecksumAndSizeValid(partFile, expectedSha256, expectedSize)) {
                    partFile.delete()
                    throw IOException("Checksum or size verification failed for ${dest.name}")
                }

                if (dest.exists()) dest.delete()
                if (!partFile.renameTo(dest)) {
                    partFile.copyTo(dest, overwrite = true)
                    partFile.delete()
                }
                return
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastErr = t
                if (attempt < 3) Thread.sleep(300L * attempt)
            }
        }
        throw lastErr ?: IOException("Failed downloading $url")
    }

    private fun isChecksumAndSizeValid(file: File, expectedSha256: String, expectedSize: Long): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        if (expectedSize > 0L && file.length() != expectedSize) return false
        if (expectedSha256.isNotBlank()) {
            val actual = DownloadEngine.computeSha256(file)
            if (!actual.equals(expectedSha256, ignoreCase = true)) return false
        }
        return true
    }

    companion object {
        fun readElfMachine(file: File): String = AndroidRuntimeVerifier.inspectElf(file).machineName
    }
}
