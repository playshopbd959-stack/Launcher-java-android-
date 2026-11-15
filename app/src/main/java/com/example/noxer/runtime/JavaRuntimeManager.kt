package com.example.noxer.runtime

import android.os.Build
import com.example.noxer.console.ConsoleLogger
import com.example.noxer.core.NoxerPaths
import com.example.noxer.data.db.RuntimeDao
import com.example.noxer.data.db.RuntimeEntity
import com.example.noxer.download.DownloadEngine
import com.example.noxer.nativebridge.NoxerNativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

@Serializable
private data class AdoptiumRelease(
    @SerialName("release_name") val releaseName: String = "",
    val binaries: List<AdoptiumBinary> = emptyList(),
)

@Serializable
private data class AdoptiumBinary(
    val architecture: String = "",
    val os: String = "",
    @SerialName("image_type") val imageType: String = "",
    @SerialName("package") val pkg: AdoptiumPackage? = null,
)

@Serializable
private data class AdoptiumPackage(
    val name: String = "",
    val link: String = "",
    val checksum: String = "",
    val size: Long = 0L,
)

data class RuntimeInstallProgress(
    val isInstalling: Boolean = false,
    val majorVersion: Int = 0,
    val statusText: String = "",
    val progressFraction: Float = 0f,
)

data class RuntimeDiagnostics(
    val hostAbis: List<String>,
    val primaryAbi: String,
    val isArm64Host: Boolean,
    val nativeBridgeLoaded: Boolean,
    val eglReport: String,
)

class JavaRuntimeManager(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val runtimeDao: RuntimeDao,
    private val paths: NoxerPaths,
    private val consoleLogger: ConsoleLogger,
) {
    val runtimes: Flow<List<RuntimeEntity>> = runtimeDao.observeAll()

    private val _installProgress = MutableStateFlow(RuntimeInstallProgress())
    val installProgress: StateFlow<RuntimeInstallProgress> = _installProgress.asStateFlow()

    fun getHostDiagnostics(): RuntimeDiagnostics {
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty().ifEmpty {
            listOf(NoxerNativeBridge.getArchitecture())
        }
        val primary = abis.firstOrNull() ?: "unknown"
        return RuntimeDiagnostics(
            hostAbis = abis,
            primaryAbi = primary,
            isArm64Host = abis.any { it.contains("arm64", ignoreCase = true) || it.contains("aarch64", ignoreCase = true) },
            nativeBridgeLoaded = NoxerNativeBridge.isNativeLibraryLoaded(),
            eglReport = NoxerNativeBridge.probeEglCapabilities(),
        )
    }

    fun resolveAdoptiumArch(): Pair<String, String> {
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        return when {
            abis.any { it == "arm64-v8a" } -> "aarch64" to "arm64-v8a"
            abis.any { it == "x86_64" } -> "x64" to "x86_64"
            abis.any { it == "armeabi-v7a" } -> "arm" to "armeabi-v7a"
            else -> "aarch64" to "arm64-v8a"
        }
    }

    suspend fun fetchAndInstallRuntime(majorVersion: Int): Result<RuntimeEntity> = withContext(Dispatchers.IO) {
        val (adoptiumArch, androidAbi) = resolveAdoptiumArch()
        _installProgress.value = RuntimeInstallProgress(
            isInstalling = true,
            majorVersion = majorVersion,
            statusText = "Querying Eclipse Temurin Java $majorVersion ($adoptiumArch)...",
            progressFraction = 0.05f,
        )

        try {
            val apiUrl = "https://api.adoptium.net/v3/assets/feature_releases/$majorVersion/ga" +
                "?architecture=$adoptiumArch&os=linux&image_type=jre&jvm_impl=hotspot&page_size=5"
            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "NoxerLauncher/1.0 (Android)")
                .build()
            val bodyStr = okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Adoptium API HTTP ${resp.code}")
                resp.body?.string() ?: throw IOException("Empty response from Adoptium API")
            }

            val releases = json.decodeFromString<List<AdoptiumRelease>>(bodyStr)
            val matchedRelease = releases.firstOrNull { rel ->
                rel.binaries.any { it.pkg?.link?.endsWith(".tar.gz") == true }
            } ?: throw IOException("No Linux $adoptiumArch JRE .tar.gz release found for Java $majorVersion")

            val pkg = matchedRelease.binaries.first { it.pkg?.link?.endsWith(".tar.gz") == true }.pkg!!
            val runtimeId = "temurin-${majorVersion}-$androidAbi"
            val targetDir = paths.runtimeDir(runtimeId)
            val archiveFile = File(paths.runtimesDir, "$runtimeId.tar.gz")

            _installProgress.value = RuntimeInstallProgress(
                isInstalling = true,
                majorVersion = majorVersion,
                statusText = "Downloading ${matchedRelease.releaseName} (${pkg.size / (1024 * 1024)} MB)...",
                progressFraction = 0.15f,
            )

            val dlReq = Request.Builder().url(pkg.link).build()
            okHttpClient.newCall(dlReq).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Runtime download HTTP ${resp.code}")
                val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: pkg.size
                var readBytes = 0L
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(archiveFile).use { out ->
                        val buf = ByteArray(32 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            readBytes += n
                            val frac = if (total > 0) 0.15f + 0.55f * (readBytes.toFloat() / total) else 0.4f
                            _installProgress.value = _installProgress.value.copy(progressFraction = frac.coerceIn(0.15f, 0.70f))
                        }
                    }
                }
            }

            if (pkg.checksum.isNotBlank()) {
                _installProgress.value = _installProgress.value.copy(
                    statusText = "Verifying SHA-256 checksum...",
                    progressFraction = 0.72f,
                )
                val actualSha256 = DownloadEngine.computeSha256(archiveFile)
                if (!actualSha256.equals(pkg.checksum, ignoreCase = true)) {
                    archiveFile.delete()
                    throw IOException("Runtime SHA-256 mismatch: expected ${pkg.checksum}, got $actualSha256")
                }
            }

            _installProgress.value = _installProgress.value.copy(
                statusText = "Extracting OpenJDK $majorVersion archive...",
                progressFraction = 0.80f,
            )
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()
            extractTarGzStrippingRoot(archiveFile, targetDir)
            archiveFile.delete()

            val libJvm = findLibJvm(targetDir)
            val verificationNote = verifyRuntimeBinary(libJvm, androidAbi)

            val entity = RuntimeEntity(
                id = runtimeId,
                displayName = "Eclipse Temurin JRE $majorVersion ($androidAbi)",
                majorVersion = majorVersion,
                fullVersion = matchedRelease.releaseName,
                abi = androidAbi,
                installPath = targetDir.absolutePath,
                libJvmPath = libJvm?.absolutePath.orEmpty(),
                sha256 = pkg.checksum,
                sizeBytes = pkg.size,
                downloadUrl = pkg.link,
                isInstalled = libJvm != null,
                isVerified = verificationNote.startsWith("OK"),
                diagnosticMessage = verificationNote,
                installedAt = System.currentTimeMillis(),
            )
            runtimeDao.upsert(entity)
            consoleLogger.info("JavaRuntime", "Installed ${entity.displayName}: $verificationNote")
            Result.success(entity)
        } catch (t: Throwable) {
            consoleLogger.error("JavaRuntime", "Failed to install Java $majorVersion: ${t.message}")
            Result.failure(t)
        } finally {
            _installProgress.value = RuntimeInstallProgress()
        }
    }

    suspend fun verifyRuntime(runtime: RuntimeEntity): RuntimeEntity = withContext(Dispatchers.IO) {
        val libJvm = File(runtime.libJvmPath).takeIf { it.exists() } ?: findLibJvm(File(runtime.installPath))
        val note = verifyRuntimeBinary(libJvm, runtime.abi)
        val updated = runtime.copy(
            libJvmPath = libJvm?.absolutePath.orEmpty(),
            isInstalled = libJvm != null,
            isVerified = note.startsWith("OK"),
            diagnosticMessage = note,
        )
        runtimeDao.upsert(updated)
        updated
    }

    suspend fun deleteRuntime(runtime: RuntimeEntity) = withContext(Dispatchers.IO) {
        File(runtime.installPath).deleteRecursively()
        runtimeDao.deleteById(runtime.id)
    }

    private fun verifyRuntimeBinary(libJvm: File?, expectedAbi: String): String {
        if (libJvm == null || !libJvm.exists()) {
            return "ERROR: libjvm.so not found inside extracted runtime directory."
        }
        val elfArch = readElfMachine(libJvm)
        val abiMatch = when (expectedAbi) {
            "arm64-v8a" -> elfArch == "AArch64"
            "x86_64" -> elfArch == "x86-64"
            "armeabi-v7a" -> elfArch == "ARM"
            else -> false
        }
        if (!abiMatch) {
            return "ARCH_MISMATCH: libjvm.so ELF machine is $elfArch, host requires $expectedAbi."
        }
        val jniProbe = NoxerNativeBridge.probeLibJvm(libJvm.absolutePath)
        return if (jniProbe.startsWith("OK")) {
            "OK: ELF $elfArch verified & JNI_CreateJavaVM symbol loadable"
        } else {
            // Standard glibc Linux Temurin binaries link against ld-linux-aarch64.so.1/libc.so.6 instead of Android Bionic libc.so
            "ELF $elfArch verified on disk. Native dlopen note: $jniProbe"
        }
    }

    private fun findLibJvm(root: File): File? =
        root.walkTopDown().firstOrNull { it.isFile && it.name == "libjvm.so" }

    private fun extractTarGzStrippingRoot(archive: File, destDir: File) {
        val canonicalDest = destDir.canonicalPath
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val relName = entry.name.substringAfter('/', missingDelimiterValue = entry.name)
                if (relName.isNotBlank()) {
                    val outFile = File(destDir, relName)
                    // Zip-Slip protection
                    if (!outFile.canonicalPath.startsWith(canonicalDest)) {
                        throw SecurityException("Blocked unsafe tar path: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else if (!entry.isSymbolicLink) {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                        if ((entry.mode and 0b001001001) != 0) {
                            outFile.setExecutable(true, false)
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
    }

    companion object {
        fun readElfMachine(file: File): String {
            if (!file.exists() || file.length() < 20) return "Invalid"
            val header = ByteArray(20)
            file.inputStream().use { it.read(header) }
            if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
            ) return "Not-ELF"
            val eMachine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
            return when (eMachine) {
                0xB7 -> "AArch64"
                0x3E -> "x86-64"
                0x28 -> "ARM"
                0x03 -> "x86"
                else -> "ELF(0x${eMachine.toString(16)})"
            }
        }
    }
}
