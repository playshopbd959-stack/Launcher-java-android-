package com.example.noxer.storage

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.example.noxer.console.ConsoleLogger
import com.example.noxer.core.NoxerPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class StorageBreakdown(
    val versionsBytes: Long,
    val librariesBytes: Long,
    val assetsBytes: Long,
    val runtimesBytes: Long,
    val instancesBytes: Long,
    val logsAndCrashesBytes: Long,
    val backupsBytes: Long,
    val availableDeviceBytes: Long,
) {
    val totalUsedBytes: Long
        get() = versionsBytes + librariesBytes + assetsBytes + runtimesBytes + instancesBytes + logsAndCrashesBytes + backupsBytes
}

data class DirectoryItemInfo(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
)

class StorageManager(
    private val context: Context,
    private val paths: NoxerPaths,
    private val consoleLogger: ConsoleLogger,
) {
    suspend fun computeBreakdown(): StorageBreakdown = withContext(Dispatchers.IO) {
        val stat = StatFs(paths.rootDir.absolutePath)
        StorageBreakdown(
            versionsBytes = dirSize(paths.versionsDir),
            librariesBytes = dirSize(paths.librariesDir),
            assetsBytes = dirSize(paths.assetsDir),
            runtimesBytes = dirSize(paths.runtimesDir),
            instancesBytes = dirSize(paths.instancesDir),
            logsAndCrashesBytes = dirSize(paths.launcherLogsDir) + dirSize(paths.crashReportsDir),
            backupsBytes = dirSize(paths.backupsDir),
            availableDeviceBytes = stat.availableBytes,
        )
    }

    suspend fun listDirectory(dir: File): List<DirectoryItemInfo> = withContext(Dispatchers.IO) {
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
        dir.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?.map { f ->
                DirectoryItemInfo(
                    file = f,
                    name = f.name,
                    isDirectory = f.isDirectory,
                    sizeBytes = if (f.isDirectory) 0L else f.length(),
                    lastModified = f.lastModified(),
                )
            }.orEmpty()
    }

    suspend fun backupDirectoryToUri(sourceDir: File, targetUri: Uri): Result<Long> =
        withContext(Dispatchers.IO) {
            try {
                val outStream: OutputStream = context.contentResolver.openOutputStream(targetUri)
                    ?: return@withContext Result.failure(IllegalStateException("Could not open SAF output URI"))
                var fileCount = 0L
                ZipOutputStream(outStream).use { zos ->
                    val base = sourceDir.canonicalPath
                    sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        val rel = file.canonicalPath.removePrefix(base).trimStart(File.separatorChar)
                        zos.putNextEntry(ZipEntry(rel))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        fileCount++
                    }
                }
                consoleLogger.info("Storage", "Exported $fileCount files from ${sourceDir.name} to SAF backup")
                Result.success(fileCount)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    suspend fun restoreZipFromUri(sourceUri: Uri, targetDir: File): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val inStream: InputStream = context.contentResolver.openInputStream(sourceUri)
                    ?: return@withContext Result.failure(IllegalStateException("Could not open SAF input URI"))
                targetDir.mkdirs()
                val canonicalTarget = targetDir.canonicalPath
                var count = 0
                ZipInputStream(inStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(targetDir, entry.name)
                        if (!outFile.canonicalPath.startsWith(canonicalTarget)) {
                            throw SecurityException("Blocked unsafe ZIP path: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { zis.copyTo(it) }
                            count++
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                consoleLogger.info("Storage", "Restored $count files into ${targetDir.name}")
                Result.success(count)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    suspend fun copyUriToFile(uri: Uri, destFile: File): Result<File> = withContext(Dispatchers.IO) {
        try {
            destFile.parentFile?.mkdirs()
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("Cannot read selected file"))
            input.use { src ->
                FileOutputStream(destFile).use { dst -> src.copyTo(dst) }
            }
            Result.success(destFile)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return "%.1f KB".format(kb)
            val mb = kb / 1024.0
            if (mb < 1024.0) return "%.1f MB".format(mb)
            return "%.2f GB".format(mb / 1024.0)
        }
    }
}
