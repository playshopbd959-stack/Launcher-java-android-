package com.example.noxer.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class LoaderType(val displayName: String) {
    VANILLA("Vanilla"),
    FABRIC("Fabric"),
    FORGE("Forge"),
    NEOFORGE("NeoForge"),
    OPTIFINE("OptiFine"),
}

enum class InstallationStatus(val label: String) {
    NOT_INSTALLED("Not Downloaded"),
    DOWNLOADING("Downloading"),
    VERIFYING("Verifying"),
    READY("Ready"),
    CORRUPTED("Needs Repair"),
    ERROR("Error"),
}

@Entity(tableName = "installations")
data class InstallationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val minecraftVersionId: String,
    val loaderType: String = LoaderType.VANILLA.name,
    val loaderVersion: String? = null,
    val javaRuntimeId: String = "auto",
    val gameDirPath: String,
    val memoryMb: Int = 2048,
    val jvmArgs: String = "-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20",
    val resolutionWidth: Int = 1280,
    val resolutionHeight: Int = 720,
    val resolutionScalePercent: Int = 100,
    val fullscreen: Boolean = true,
    val rendererId: String = "GL4ES_GLES3",
    val controlProfileId: String = "default_touch",
    val status: String = InstallationStatus.NOT_INSTALLED.name,
    val lastError: String? = null,
    val lastPlayedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val verifiedFileCount: Int = 0,
    val totalFileCount: Int = 0,
)

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val uuid: String,
    val username: String,
    val encryptedMcAccessToken: String,
    val encryptedMsRefreshToken: String,
    val encryptedXstsHash: String,
    val expiresAtEpochSec: Long,
    val skinUrl: String? = null,
    val ownsMinecraft: Boolean = false,
    val isActive: Boolean = false,
    val lastAuthenticatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "offline_profiles")
data class OfflineProfileEntity(
    @PrimaryKey val id: String,
    val username: String,
    val displayName: String,
    val iconId: String = "SHIELD",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
    val preferredInstallationId: String? = null,
)

@Entity(tableName = "runtimes")
data class RuntimeEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val majorVersion: Int,
    val fullVersion: String,
    val abi: String,
    val installPath: String,
    val libJvmPath: String,
    val javaBinPath: String = "",
    val sourceProvider: String = "",
    val isDefault: Boolean = false,
    val sha256: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val isInstalled: Boolean = false,
    val isVerified: Boolean = false,
    val diagnosticMessage: String? = null,
    val installedAt: Long = 0L,
)

@Entity(tableName = "control_profiles")
data class ControlProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isDefault: Boolean = false,
    val touchButtonsJson: String,
    val keybindsJson: String,
    val mouseSensitivity: Float = 1.0f,
    val invertY: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "cached_versions")
data class CachedVersionEntity(
    @PrimaryKey val id: String,
    val type: String,
    val url: String,
    val time: String,
    val releaseTime: String,
    val sha1: String,
    val complianceLevel: Int,
    val manifestIndex: Int,
)

@Entity(tableName = "console_logs")
data class ConsoleLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val level: String, // INFO, WARN, ERROR, DEBUG
    val source: String,
    val message: String,
)

@Entity(tableName = "crash_reports")
data class CrashReportEntity(
    @PrimaryKey val id: String,
    val installationId: String,
    val installationName: String,
    val minecraftVersion: String,
    val timestamp: Long = System.currentTimeMillis(),
    val exitCode: Int,
    val category: String,
    val summary: String,
    val recoveryInstructions: String,
    val reportFilePath: String,
    val rawExcerpt: String,
)
