package com.example.noxer.launch

import com.example.noxer.core.NoxerPaths
import com.example.noxer.data.db.CrashReportDao
import com.example.noxer.data.db.CrashReportEntity
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class LaunchErrorCategory(val title: String) {
    MISSING_JAVA_RUNTIME("Missing Java Runtime"),
    UNSUPPORTED_ARCHITECTURE("Unsupported Architecture / Linker ABI"),
    FAILED_DOWNLOADS("Failed Downloads"),
    CORRUPTED_FILES("Corrupted or Missing Game Files"),
    AUTHENTICATION_ERROR("Microsoft / Entitlement Error"),
    INSUFFICIENT_STORAGE("Insufficient Storage"),
    MEMORY_ERROR("Out of Memory / Heap Allocation Error"),
    UNSUPPORTED_RENDERER("Unsupported Renderer / OpenGL ES"),
    MISSING_LIBRARIES("Missing Libraries or Native Bridge"),
    INCOMPATIBLE_MODS("Incompatible or Conflicting Mods"),
    FAILED_GAME_LAUNCH("Game Process Exited with Error"),
}

class CrashHandler(
    private val crashReportDao: CrashReportDao,
    private val paths: NoxerPaths,
) {
    val crashReports: Flow<List<CrashReportEntity>> = crashReportDao.observeAll()

    suspend fun recordCrash(
        installationId: String,
        installationName: String,
        minecraftVersion: String,
        exitCode: Int,
        rawLogs: List<String>,
        overrideCategory: LaunchErrorCategory? = null,
        explicitSummary: String? = null,
    ): CrashReportEntity {
        val joined = rawLogs.takeLast(120).joinToString("\n")
        val (category, summary, recovery) = classifyFailure(
            exitCode = exitCode,
            joinedLogs = joined,
            overrideCategory = overrideCategory,
            explicitSummary = explicitSummary,
        )

        val timestamp = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(timestamp))
        val reportFile = File(paths.crashReportsDir, "crash-$stamp-${NoxerPaths.sanitizeSegment(minecraftVersion)}.txt")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(
            buildString {
                appendLine("---- NOXER LAUNCHER CRASH REPORT ----")
                appendLine("Timestamp: ${Date(timestamp)}")
                appendLine("Installation: $installationName ($installationId)")
                appendLine("Minecraft Version: $minecraftVersion")
                appendLine("Category: ${category.title}")
                appendLine("Exit Code: $exitCode")
                appendLine("Summary: $summary")
                appendLine("Recovery Instructions: $recovery")
                appendLine()
                appendLine("--- RECENT LOG OUTPUT ---")
                appendLine(joined.ifBlank { "(No stdout/stderr output captured)" })
            }
        )

        val entity = CrashReportEntity(
            id = UUID.randomUUID().toString().take(8),
            installationId = installationId,
            installationName = installationName,
            minecraftVersion = minecraftVersion,
            timestamp = timestamp,
            exitCode = exitCode,
            category = category.title,
            summary = summary,
            recoveryInstructions = recovery,
            reportFilePath = reportFile.absolutePath,
            rawExcerpt = joined.takeLast(2500),
        )
        crashReportDao.upsert(entity)
        return entity
    }

    suspend fun deleteReport(id: String) {
        crashReportDao.deleteById(id)
    }

    companion object {
        fun classifyFailure(
            exitCode: Int,
            joinedLogs: String,
            overrideCategory: LaunchErrorCategory? = null,
            explicitSummary: String? = null,
        ): Triple<LaunchErrorCategory, String, String> {
            if (overrideCategory != null) {
                return Triple(
                    overrideCategory,
                    explicitSummary ?: overrideCategory.title,
                    defaultRecoveryFor(overrideCategory),
                )
            }
            val lower = joinedLogs.lowercase()
            val detected = when {
                lower.contains("outofmemoryerror") || lower.contains("could not reserve enough space") ->
                    LaunchErrorCategory.MEMORY_ERROR
                lower.contains("ld-linux") || lower.contains("bionic") || lower.contains("wrong elf") || lower.contains("dlopen failed") ->
                    LaunchErrorCategory.UNSUPPORTED_ARCHITECTURE
                lower.contains("libjvm") || lower.contains("java runtime") ->
                    LaunchErrorCategory.MISSING_JAVA_RUNTIME
                lower.contains("entitlement") || lower.contains("401") || lower.contains("access token") ->
                    LaunchErrorCategory.AUTHENTICATION_ERROR
                lower.contains("no space left") || lower.contains("enospc") ->
                    LaunchErrorCategory.INSUFFICIENT_STORAGE
                lower.contains("unsatisfiedlinkerror") || lower.contains("lwjgl") || lower.contains("glfw") ->
                    LaunchErrorCategory.MISSING_LIBRARIES
                lower.contains("modresolutionexception") || lower.contains("mixin") || lower.contains("fabricloader") ->
                    LaunchErrorCategory.INCOMPATIBLE_MODS
                lower.contains("sha-1") || lower.contains("corrupt") || lower.contains("zipexception") ->
                    LaunchErrorCategory.CORRUPTED_FILES
                lower.contains("egl") || lower.contains("opengl") || lower.contains("glx") ->
                    LaunchErrorCategory.UNSUPPORTED_RENDERER
                else -> LaunchErrorCategory.FAILED_GAME_LAUNCH
            }
            val summary = explicitSummary ?: "${detected.title} (exit code $exitCode)"
            return Triple(detected, summary, defaultRecoveryFor(detected))
        }

        fun defaultRecoveryFor(category: LaunchErrorCategory): String = when (category) {
            LaunchErrorCategory.MISSING_JAVA_RUNTIME ->
                "Open Settings -> Java Runtimes and download the compatible Java 8/17/21 ARM64 runtime."
            LaunchErrorCategory.UNSUPPORTED_ARCHITECTURE ->
                "Standard Linux glibc libjvm.so cannot link directly against Android Bionic libc without an Android-compiled Bionic OpenJDK build. Install an Android-bionic ARM64 JRE or check Native Diagnostics."
            LaunchErrorCategory.FAILED_DOWNLOADS ->
                "Check internet connectivity, reduce download concurrency in Settings, and click Repair on the installation."
            LaunchErrorCategory.CORRUPTED_FILES ->
                "Run REPAIR from My Installations to re-verify SHA-1 checksums and re-download damaged JARs or assets."
            LaunchErrorCategory.AUTHENTICATION_ERROR ->
                "Open Accounts, sign in with Microsoft OAuth2, and verify Minecraft: Java Edition ownership."
            LaunchErrorCategory.INSUFFICIENT_STORAGE ->
                "Free internal device storage or clean unused runtimes/backups in Settings -> Storage."
            LaunchErrorCategory.MEMORY_ERROR ->
                "Lower RAM allocation in Installation Edit / Settings (e.g. 1536–2048 MB) so it fits within available device memory."
            LaunchErrorCategory.UNSUPPORTED_RENDERER ->
                "Switch the renderer backend between GL4ES, Holy-GL4ES, or Zink in Installation Edit."
            LaunchErrorCategory.MISSING_LIBRARIES ->
                "Minecraft desktop LWJGL/GLFW natives require Android-patched LWJGL + GL4ES native bridges. Run Repair or check Native Compatibility Diagnostics."
            LaunchErrorCategory.INCOMPATIBLE_MODS ->
                "Open Mods, inspect yellow conflict warnings, disable incompatible mods, and verify loader version."
            LaunchErrorCategory.FAILED_GAME_LAUNCH ->
                "Inspect the full crash log in Console -> Crash Reports for stack trace details."
        }
    }
}
