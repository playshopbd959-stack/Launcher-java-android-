package com.example.noxer.runtime

import java.io.File

enum class RuntimeArchiveFormat {
    TAR_XZ,
    TAR_GZ,
    ZIP,
    DEB,
}

data class AndroidAbiInfo(
    val primaryAbi: String,
    val supportedAbis: List<String>,
    val isArm64Compatible: Boolean,
    val elfMachineName: String,
    val pojavArchAliases: List<String>,
    val termuxArch: String,
)

data class CompanionRuntimePackage(
    val packageName: String,
    val version: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val format: RuntimeArchiveFormat = RuntimeArchiveFormat.DEB,
)

data class ResolvedRuntimePackage(
    val majorVersion: Int,
    val fullVersion: String,
    val displayName: String,
    val providerName: String,
    val targetAbi: String,
    val elfMachineExpected: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val format: RuntimeArchiveFormat,
    val isSplitUniversalWithBin: Boolean = false,
    val companionPackages: List<CompanionRuntimePackage> = emptyList(),
)

data class RuntimeVerificationResult(
    val isVerified: Boolean,
    val elfMachine: String,
    val isAndroidBionic: Boolean,
    val javaHomePath: String,
    val libJvmPath: String,
    val javaBinPath: String,
    val detectedJavaVersion: String,
    val executionMethod: String,
    val diagnosticMessage: String,
)

data class RuntimeInstallProgress(
    val isInstalling: Boolean = false,
    val majorVersion: Int = 0,
    val phase: String = "",
    val statusText: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val progressFraction: Float = 0f,
)

data class RuntimeDiagnostics(
    val hostAbis: List<String>,
    val primaryAbi: String,
    val isArm64Host: Boolean,
    val nativeBridgeLoaded: Boolean,
    val eglReport: String,
)

fun interface RuntimeExecutionProbe {
    suspend fun verifyInIsolatedProcess(libJvmPath: String, javaHome: String): String
}

interface RuntimeSourceResolver {
    fun detectAbiInfo(): AndroidAbiInfo
    suspend fun resolvePackages(majorVersion: Int, abi: AndroidAbiInfo): List<ResolvedRuntimePackage>
}

data class RuntimeEnvironment(
    val javaHome: File,
    val libJvmFile: File,
    val javaBinFile: File?,
    val libraryPaths: List<String>,
    val envVars: Map<String, String>,
)
