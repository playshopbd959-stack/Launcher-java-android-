package com.example.noxer.download

import com.example.noxer.console.ConsoleLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class DownloadProgressState(
    val isActive: Boolean = false,
    val phaseLabel: String = "Idle",
    val currentFileLabel: String = "",
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val failedFiles: List<String> = emptyList(),
    val isCancelled: Boolean = false,
) {
    val fraction: Float
        get() = when {
            totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            totalFiles > 0 -> (completedFiles.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
}

data class IntegrityReport(
    val totalChecked: Int,
    val validCount: Int,
    val missingTasks: List<DownloadTask>,
    val corruptedTasks: List<DownloadTask>,
) {
    val isHealthy: Boolean get() = missingTasks.isEmpty() && corruptedTasks.isEmpty()
}

class DownloadEngine(
    private val okHttpClient: OkHttpClient,
    private val consoleLogger: ConsoleLogger,
) {
    private val _progress = MutableStateFlow(DownloadProgressState())
    val progress: StateFlow<DownloadProgressState> = _progress.asStateFlow()

    @Volatile
    private var cancelRequested = false

    fun requestCancel() {
        cancelRequested = true
        _progress.value = _progress.value.copy(isCancelled = true, phaseLabel = "Cancelling downloads...")
    }

    suspend fun verifyIntegrity(tasks: List<DownloadTask>): IntegrityReport = withContext(Dispatchers.IO) {
        val missing = mutableListOf<DownloadTask>()
        val corrupted = mutableListOf<DownloadTask>()
        var valid = 0

        for (task in tasks) {
            val file = task.destination
            if (!file.exists() || file.length() == 0L) {
                missing += task
                continue
            }
            if (!verifyFileSha1AndSize(file, task.expectedSha1, task.expectedSize)) {
                corrupted += task
            } else {
                valid++
            }
        }
        IntegrityReport(
            totalChecked = tasks.size,
            validCount = valid,
            missingTasks = missing,
            corruptedTasks = corrupted,
        )
    }

    suspend fun downloadAll(
        phaseLabel: String,
        tasks: List<DownloadTask>,
        concurrency: Int = 6,
        maxRetries: Int = 3,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        cancelRequested = false
        val totalFiles = tasks.size
        val totalBytes = tasks.sumOf { it.expectedSize.coerceAtLeast(0L) }
        val completedCount = AtomicInteger(0)
        val downloadedBytes = AtomicLong(0L)
        val failed = mutableListOf<String>()

        _progress.value = DownloadProgressState(
            isActive = true,
            phaseLabel = phaseLabel,
            totalFiles = totalFiles,
            totalBytes = totalBytes,
        )

        if (tasks.isEmpty()) {
            _progress.value = DownloadProgressState(isActive = false, phaseLabel = "Up to date")
            return@withContext Result.success(Unit)
        }

        val semaphore = Semaphore(concurrency.coerceIn(1, 16))

        try {
            coroutineScope {
                tasks.map { task ->
                    async {
                        semaphore.withPermit {
                            if (cancelRequested) throw CancellationException("User cancelled download")

                            // Fast-path skip if already valid on disk
                            if (task.destination.exists() &&
                                verifyFileSha1AndSize(task.destination, task.expectedSha1, task.expectedSize)
                            ) {
                                val done = completedCount.incrementAndGet()
                                val bytes = downloadedBytes.addAndGet(task.destination.length())
                                _progress.value = _progress.value.copy(
                                    currentFileLabel = task.label,
                                    completedFiles = done,
                                    downloadedBytes = bytes,
                                )
                                return@withPermit
                            }

                            var attempt = 0
                            var success = false
                            var lastError: Throwable? = null
                            while (attempt < maxRetries && !success && !cancelRequested) {
                                attempt++
                                try {
                                    downloadSingleWithResume(task) { delta ->
                                        val bytes = downloadedBytes.addAndGet(delta)
                                        _progress.value = _progress.value.copy(
                                            currentFileLabel = task.label,
                                            downloadedBytes = bytes,
                                        )
                                    }
                                    success = true
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    lastError = t
                                    consoleLogger.warn("DownloadEngine", "Retry $attempt/$maxRetries for ${task.label}: ${t.message}")
                                    delay(350L * attempt)
                                }
                            }

                            if (!success) {
                                synchronized(failed) {
                                    failed += "${task.label}: ${lastError?.message ?: "unknown error"}"
                                }
                            }
                            val done = completedCount.incrementAndGet()
                            _progress.value = _progress.value.copy(
                                completedFiles = done,
                                failedFiles = failed.toList(),
                            )
                        }
                    }
                }.awaitAll()
            }

            if (cancelRequested) {
                _progress.value = _progress.value.copy(isActive = false, phaseLabel = "Cancelled", isCancelled = true)
                return@withContext Result.failure(CancellationException("Download cancelled by user"))
            }

            if (failed.isNotEmpty()) {
                val msg = "${failed.size} file(s) failed verification/download. First: ${failed.first()}"
                _progress.value = _progress.value.copy(isActive = false, phaseLabel = "Failed", failedFiles = failed.toList())
                return@withContext Result.failure(IOException(msg))
            }

            _progress.value = _progress.value.copy(isActive = false, phaseLabel = "Completed")
            Result.success(Unit)
        } catch (ce: CancellationException) {
            _progress.value = _progress.value.copy(isActive = false, phaseLabel = "Cancelled", isCancelled = true)
            Result.failure(ce)
        }
    }

    private fun downloadSingleWithResume(
        task: DownloadTask,
        onBytesDelta: (Long) -> Unit,
    ) {
        task.destination.parentFile?.mkdirs()
        val partFile = File(task.destination.parentFile, "${task.destination.name}.part")
        var existingBytes = if (partFile.exists()) partFile.length() else 0L

        val reqBuilder = Request.Builder()
            .url(task.url)
            .header("User-Agent", "NoxerLauncher/1.0 (Android)")
        if (existingBytes > 0L) {
            reqBuilder.header("Range", "bytes=$existingBytes-")
        }

        okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            val appendMode: Boolean
            when (response.code) {
                206 -> {
                    appendMode = true
                    onBytesDelta(existingBytes)
                }
                200 -> {
                    appendMode = false
                    existingBytes = 0L
                }
                416 -> {
                    // Range not satisfiable; discard stale partial file and restart clean
                    partFile.delete()
                    throw IOException("HTTP 416 range reset for ${task.label}")
                }
                else -> throw IOException("HTTP ${response.code} downloading ${task.url}")
            }

            val body = response.body ?: throw IOException("Empty body for ${task.url}")
            body.byteStream().use { input ->
                FileOutputStream(partFile, appendMode).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (cancelRequested) throw CancellationException("Cancelled")
                        output.write(buffer, 0, read)
                        onBytesDelta(read.toLong())
                    }
                    output.flush()
                }
            }
        }

        if (!verifyFileSha1AndSize(partFile, task.expectedSha1, task.expectedSize)) {
            partFile.delete()
            throw IOException("SHA-1 / size verification failed for ${task.label}")
        }

        if (task.destination.exists()) task.destination.delete()
        if (!partFile.renameTo(task.destination)) {
            partFile.copyTo(task.destination, overwrite = true)
            partFile.delete()
        }
    }

    companion object {
        fun verifyFileSha1AndSize(file: File, expectedSha1: String?, expectedSize: Long): Boolean {
            if (!file.exists()) return false
            if (expectedSize > 0L && file.length() != expectedSize) return false
            if (expectedSha1.isNullOrBlank()) return file.length() > 0L
            return computeSha1(file).equals(expectedSha1, ignoreCase = true)
        }

        fun computeSha1(file: File): String = computeDigest(file, "SHA-1")
        fun computeSha256(file: File): String = computeDigest(file, "SHA-256")

        private fun computeDigest(file: File, algorithm: String): String {
            val digest = MessageDigest.getInstance(algorithm)
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
