package com.example.noxer.launch

import android.content.Context
import android.content.Intent
import android.os.StatFs
import com.example.noxer.auth.AccountSessionManager
import com.example.noxer.auth.ActiveIdentity
import com.example.noxer.auth.MicrosoftAuthManager
import com.example.noxer.auth.OfflineProfileManager
import com.example.noxer.console.ConsoleLogger
import com.example.noxer.core.NoxerPaths
import com.example.noxer.data.db.InstallationDao
import com.example.noxer.data.db.InstallationEntity
import com.example.noxer.data.db.InstallationStatus
import com.example.noxer.data.db.LoaderType
import com.example.noxer.data.db.RuntimeDao
import com.example.noxer.data.db.RuntimeEntity
import com.example.noxer.download.DownloadEngine
import com.example.noxer.download.MojangRuleEvaluator
import com.example.noxer.graphics.RendererBackend
import com.example.noxer.graphics.RendererManager
import com.example.noxer.mods.FabricProfileJson
import com.example.noxer.mods.ModLoaderManager
import com.example.noxer.nativebridge.NativeLogCallback
import com.example.noxer.nativebridge.NoxerNativeBridge
import com.example.noxer.runtime.JavaRuntimeManager
import com.example.noxer.version.VersionCenterRepository
import com.example.noxer.version.VersionDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

data class LaunchPipelineState(
    val isLaunching: Boolean = false,
    val currentStep: Int = 0,
    val totalSteps: Int = 16,
    val stepTitle: String = "Idle",
    val activeInstallationName: String? = null,
    val activeInstallationId: String? = null,
    val authModeLabel: String = "",
    val runtimeDownloadProgress: Float = 0f,
    val runtimeDownloadDetail: String = "",
    val missingJavaMajor: Int? = null,
    val generatedCommandPreview: String = "",
    val errorCategory: LaunchErrorCategory? = null,
    val errorMessage: String? = null,
    val recoveryAction: String? = null,
    val isGameSurfaceRunning: Boolean = false,
)

data class PreparedLaunchPlan(
    val installation: InstallationEntity,
    val runtime: RuntimeEntity,
    val mainClass: String,
    val classpath: List<File>,
    val jvmOptions: List<String>,
    val gameArgs: List<String>,
    val envVars: Map<String, String>,
)

fun interface JvmLauncherBackend {
    fun launchJvm(
        libJvmPath: String,
        jvmOptions: Array<String>,
        mainClass: String,
        gameArgs: Array<String>,
        callback: NativeLogCallback,
    ): Int
}

class GameLauncher(
    private val context: Context,
    private val paths: NoxerPaths,
    private val installationDao: InstallationDao,
    private val runtimeDao: RuntimeDao,
    private val runtimeManager: JavaRuntimeManager,
    private val authManager: MicrosoftAuthManager,
    private val offlineProfileManager: OfflineProfileManager,
    private val accountSessionManager: AccountSessionManager,
    private val downloadEngine: DownloadEngine,
    private val modLoaderManager: ModLoaderManager,
    private val rendererManager: RendererManager,
    private val crashHandler: CrashHandler,
    private val consoleLogger: ConsoleLogger,
    private val json: Json,
    private val jvmBackend: JvmLauncherBackend = JvmLauncherBackend { libJvmPath, jvmOptions, mainClass, gameArgs, callback ->
        NoxerNativeBridge.launchJvm(libJvmPath, jvmOptions, mainClass, gameArgs, callback)
    },
    private val startViewportActivity: Boolean = true,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(LaunchPipelineState())
    val state: StateFlow<LaunchPipelineState> = _state.asStateFlow()

    @Volatile
    var currentPlan: PreparedLaunchPlan? = null
        private set

    fun clearError() {
        _state.value = LaunchPipelineState()
    }

    fun launchInstallation(installationId: String) {
        scope.launch {
            executePipeline(installationId)
        }
    }

    suspend fun executePipeline(installationId: String) {
        val capturedLogs = mutableListOf<String>()
        fun step(n: Int, title: String, name: String? = null, detail: String = "", dlFrac: Float = 0f) {
            _state.value = _state.value.copy(
                isLaunching = true,
                currentStep = n,
                stepTitle = title,
                activeInstallationName = name ?: _state.value.activeInstallationName,
                activeInstallationId = installationId,
                runtimeDownloadProgress = dlFrac,
                runtimeDownloadDetail = detail,
                missingJavaMajor = null,
                errorCategory = null,
                errorMessage = null,
                recoveryAction = null,
            )
            consoleLogger.info("Launch", "[$n/16] $title")
            capturedLogs += "[$n/16] $title"
        }

        suspend fun fail(
            install: InstallationEntity?,
            category: LaunchErrorCategory,
            message: String,
            code: Int = -1,
            missingJavaMajor: Int? = null,
        ) {
            val recovery = CrashHandler.defaultRecoveryFor(category)
            consoleLogger.error("Launch", "$message — $recovery")
            capturedLogs += "ERROR: $message"
            if (install != null) {
                crashHandler.recordCrash(
                    installationId = install.id,
                    installationName = install.name,
                    minecraftVersion = install.minecraftVersionId,
                    exitCode = code,
                    rawLogs = capturedLogs,
                    overrideCategory = category,
                    explicitSummary = message,
                )
            }
            _state.value = LaunchPipelineState(
                isLaunching = false,
                currentStep = _state.value.currentStep,
                stepTitle = "Launch Failed",
                activeInstallationName = install?.name,
                activeInstallationId = install?.id,
                missingJavaMajor = missingJavaMajor,
                errorCategory = category,
                errorMessage = message,
                recoveryAction = recovery,
            )
        }

        // Step 1: Validate selected installation
        var install = installationDao.getById(installationId)
        if (install == null) {
            fail(null, LaunchErrorCategory.FAILED_GAME_LAUNCH, "Selected installation no longer exists.")
            return
        }
        step(1, "Validating installation '${install.name}'...", install.name)

        // Check storage space (> 64 MB free)
        val freeBytes = runCatching { StatFs(paths.rootDir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (freeBytes < 64L * 1024L * 1024L) {
            fail(install, LaunchErrorCategory.INSUFFICIENT_STORAGE, "Less than 64 MB free internal storage remaining.")
            return
        }

        // Step 2: Resolve active identity strictly (Verified Microsoft vs Local Offline Profile)
        step(2, "Resolving selected player profile...")
        val activeIdentity = accountSessionManager.resolveActiveIdentityNow()
        val launchUsername: String
        val launchUuid: String
        val launchAccessToken: String
        val launchUserType: String
        val authModeLabel: String

        when (activeIdentity) {
            is ActiveIdentity.None -> {
                fail(
                    install,
                    LaunchErrorCategory.AUTHENTICATION_ERROR,
                    "No Microsoft account or offline profile selected. Open Accounts to sign in or select a local profile.",
                )
                return
            }
            is ActiveIdentity.Microsoft -> {
                val storedActive = activeIdentity.account
                val verifiedAccount = if (storedActive.expiresAtEpochSec > System.currentTimeMillis() / 1000L + 120L) {
                    storedActive
                } else {
                    authManager.refreshActiveAccount().getOrElse {
                        fail(
                            install,
                            LaunchErrorCategory.AUTHENTICATION_ERROR,
                            "Session expired and token refresh failed: ${it.message}",
                        )
                        return
                    }
                }
                if (!verifiedAccount.ownsMinecraft) {
                    fail(
                        install,
                        LaunchErrorCategory.AUTHENTICATION_ERROR,
                        "Account ${verifiedAccount.username} lacks Minecraft Java entitlement.",
                    )
                    return
                }
                launchUsername = verifiedAccount.username
                launchUuid = verifiedAccount.uuid
                launchAccessToken = authManager.decryptAccessToken(verifiedAccount)
                launchUserType = "msa"
                authModeLabel = "VERIFIED MICROSOFT ACCOUNT (${verifiedAccount.username})"
                consoleLogger.info("LaunchAuth", "Authenticated Microsoft account '${verifiedAccount.username}' (${verifiedAccount.uuid}) with verified ownership.")
            }
            is ActiveIdentity.Offline -> {
                val profile = activeIdentity.profile
                offlineProfileManager.markUsed(profile.id)
                launchUsername = profile.username
                launchUuid = profile.id
                launchAccessToken = "0"
                launchUserType = "legacy"
                authModeLabel = "OFFLINE / UNLICENSED PROFILE (${profile.username})"
                consoleLogger.info(
                    "LaunchAuth",
                    "Using UNLICENSED / OFFLINE PROFILE '${profile.username}' (local uuid=${profile.id}). Skipping Microsoft/Mojang auth; supported only for offline gameplay and offline-mode servers.",
                )
            }
        }
        _state.value = _state.value.copy(authModeLabel = authModeLabel)

        // Step 3: Autonomous Android Java Runtime detection, installation, verification & assignment
        step(3, "Detecting required Java version & Android architecture...")
        val versionJsonFile = paths.versionJsonFile(install.minecraftVersionId)
        if (!versionJsonFile.exists()) {
            fail(install, LaunchErrorCategory.CORRUPTED_FILES, "Version metadata ${install.minecraftVersionId}.json is missing. Run Repair.")
            return
        }
        val versionDetails = runCatching {
            json.decodeFromString(VersionDetails.serializer(), versionJsonFile.readText())
        }.getOrElse {
            fail(install, LaunchErrorCategory.CORRUPTED_FILES, "Corrupted version metadata JSON: ${it.message}")
            return
        }

        val requiredJava = VersionCenterRepository.inferRequiredJavaMajor(install.minecraftVersionId, versionDetails)
        val abiInfo = runtimeManager.detectAbiInfo()
        consoleLogger.info(
            "LaunchRuntime",
            "Minecraft ${install.minecraftVersionId} requires Java $requiredJava on ${abiInfo.primaryAbi} (arm64Compatible=${abiInfo.isArm64Compatible})",
        )

        val existingVerified = runtimeManager.findCompatibleVerifiedRuntime(
            majorVersion = requiredJava,
            preferredRuntimeId = install.javaRuntimeId,
        )

        val runtime = if (existingVerified != null) {
            existingVerified
        } else {
            step(
                n = 3,
                title = "Java $requiredJava missing — installing Android (${abiInfo.primaryAbi}) runtime...",
                detail = "Resolving Android OpenJDK $requiredJava...",
                dlFrac = 0.03f,
            )
            val installRes = runtimeManager.ensureRuntimeInstalledAndVerified(
                majorVersion = requiredJava,
                preferredRuntimeId = install.javaRuntimeId,
            ) { progress ->
                _state.value = _state.value.copy(
                    isLaunching = true,
                    currentStep = 3,
                    stepTitle = "Installing Java $requiredJava (${abiInfo.primaryAbi}) — ${progress.phase}",
                    runtimeDownloadProgress = progress.progressFraction,
                    runtimeDownloadDetail = progress.statusText,
                )
            }

            installRes.getOrElse { err ->
                val msg = err.message ?: "Unable to install Java $requiredJava"
                val cat = when {
                    msg.contains("storage", ignoreCase = true) -> LaunchErrorCategory.INSUFFICIENT_STORAGE
                    msg.contains("ARCH_MISMATCH", ignoreCase = true) || msg.contains("GLIBC", ignoreCase = true) ->
                        LaunchErrorCategory.UNSUPPORTED_ARCHITECTURE
                    else -> LaunchErrorCategory.MISSING_JAVA_RUNTIME
                }
                fail(
                    install = install,
                    category = cat,
                    message = "Minecraft ${install.minecraftVersionId} requires Java $requiredJava (${abiInfo.primaryAbi}): $msg",
                    missingJavaMajor = requiredJava,
                )
                return
            }
        }

        // Automatically assign verified runtime to the selected installation
        if (install.javaRuntimeId != runtime.id) {
            install = install.copy(javaRuntimeId = runtime.id)
            installationDao.upsert(install)
            consoleLogger.info("LaunchRuntime", "Assigned ${runtime.displayName} (${runtime.id}) to '${install.name}'")
        }
        _state.value = _state.value.copy(runtimeDownloadProgress = 0f, runtimeDownloadDetail = "")

        // Step 4: Check game files
        step(4, "Verifying client JAR & library SHA-1 integrity...")
        val tasks = MojangRuleEvaluator.collectDownloadTasks(versionDetails, paths, assetIndex = null)
        val integrity = downloadEngine.verifyIntegrity(tasks)
        if (!integrity.isHealthy || install.status != InstallationStatus.READY.name) {
            fail(
                install,
                LaunchErrorCategory.CORRUPTED_FILES,
                "${integrity.missingTasks.size} missing and ${integrity.corruptedTasks.size} corrupted game files detected.",
            )
            return
        }

        // Step 5: Prepare assets
        step(5, "Preparing Minecraft asset index (${versionDetails.assetIndex?.id ?: "legacy"})...")
        val gameDir = File(install.gameDirPath).apply { mkdirs() }
        val nativesDir = File(gameDir, "natives").apply { mkdirs() }

        // Step 6: Prepare native libraries
        step(6, "Preparing JNI & native library paths...")
        val modCheck = modLoaderManager.scanInstanceMods(
            install.id,
            runCatching { LoaderType.valueOf(install.loaderType) }.getOrDefault(LoaderType.VANILLA),
        )
        val modConflict = modCheck.firstOrNull { it.conflictWarning != null }
        if (modConflict != null) {
            fail(install, LaunchErrorCategory.INCOMPATIBLE_MODS, "${modConflict.displayName}: ${modConflict.conflictWarning}")
            return
        }

        // Step 7: Generate classpath
        step(7, "Generating JVM classpath...")
        val cpFiles = MojangRuleEvaluator.collectClasspathFiles(versionDetails, paths).toMutableList()
        var effectiveMainClass = versionDetails.mainClass
        val loaderType = runCatching { LoaderType.valueOf(install.loaderType) }.getOrDefault(LoaderType.VANILLA)
        if (loaderType == LoaderType.FABRIC) {
            val fabricProfileFile = File(gameDir, "fabric-profile.json")
            if (fabricProfileFile.exists()) {
                val fabricProfile = json.decodeFromString(FabricProfileJson.serializer(), fabricProfileFile.readText())
                effectiveMainClass = fabricProfile.mainClass
                fabricProfile.libraries.forEach { lib ->
                    val f = paths.libraryFile(MojangRuleEvaluator.mavenCoordinateToPath(lib.name))
                    if (f.exists()) cpFiles += f
                }
            }
        }
        val classpathStr = cpFiles.distinctBy { it.absolutePath }.joinToString(":") { it.absolutePath }

        // Step 8: Generate JVM arguments compatible with Java 8 / 17 / 21 / 25+
        step(8, "Building Java ${runtime.majorVersion} arguments (-Xmx${install.memoryMb}M)...")
        val runtimeEnv = runtimeManager.buildRuntimeEnvironment(runtime, nativesDir)
        val jvmOptions = buildCompatibleJvmOptions(
            install = install,
            runtime = runtime,
            classpathStr = classpathStr,
            nativesDir = nativesDir,
            appNativeLibDir = context.applicationInfo.nativeLibraryDir.orEmpty(),
        )

        // Step 9: Generate Minecraft game arguments
        step(9, "Resolving Minecraft game arguments...")
        val gameArgs = buildGameArguments(
            details = versionDetails,
            username = launchUsername,
            uuid = launchUuid,
            accessToken = launchAccessToken,
            userType = launchUserType,
            gameDir = gameDir,
            assetsDir = paths.assetsDir,
            width = (install.resolutionWidth * install.resolutionScalePercent / 100).coerceAtLeast(320),
            height = (install.resolutionHeight * install.resolutionScalePercent / 100).coerceAtLeast(240),
        )

        // Step 10: Configure graphics & Java runtime environment
        step(10, "Configuring ${install.rendererId} graphics & JAVA_HOME...")
        val backend = RendererBackend.entries.firstOrNull { it.id == install.rendererId } ?: RendererBackend.GL4ES_GLES3
        val envVars = rendererManager.getEnvironmentVariables(backend, install.resolutionScalePercent).toMutableMap()
        envVars["HOME"] = gameDir.absolutePath
        envVars.putAll(runtimeEnv.envVars)
        envVars.forEach { (k, v) -> NoxerNativeBridge.setEnv(k, v) }

        val plan = PreparedLaunchPlan(
            installation = install,
            runtime = runtime,
            mainClass = effectiveMainClass,
            classpath = cpFiles,
            jvmOptions = jvmOptions,
            gameArgs = gameArgs,
            envVars = envVars,
        )
        currentPlan = plan

        val previewCmd = "${runtime.libJvmPath} ${jvmOptions.filter { !it.startsWith("-Djava.class.path=") }.joinToString(" ")} -cp <${cpFiles.size} jars> $effectiveMainClass ${gameArgs.joinToString(" ")}"
        _state.value = _state.value.copy(generatedCommandPreview = previewCmd)
        consoleLogger.debug("LaunchCmd", previewCmd)

        // Step 11: Initialize rendering and input viewport
        step(11, "Opening EGL / OpenGL ES 3.0 viewport & input engine...")
        installationDao.upsert(install.copy(lastPlayedAt = System.currentTimeMillis()))

        if (startViewportActivity) {
            val intent = Intent(context, GameViewportActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(GameViewportActivity.EXTRA_INSTANCE_ID, install.id)
            }
            context.startActivity(intent)
        }

        // Steps 12-16: Start JVM via JNI bridge, capture logs & handle exit/crash
        step(12, "Starting Java ${runtime.majorVersion} VM (${runtime.abi})...")
        val jvmProbe = NoxerNativeBridge.probeLibJvm(runtime.libJvmPath)
        consoleLogger.info("JVM", "libjvm.so probe: $jvmProbe")

        step(13, "Displaying real game viewport & forwarding input...")
        step(14, "Monitoring JVM execution & process output...")
        val exitCode = jvmBackend.launchJvm(
            libJvmPath = runtime.libJvmPath,
            jvmOptions = jvmOptions.toTypedArray(),
            mainClass = effectiveMainClass,
            gameArgs = gameArgs.toTypedArray(),
            callback = object : NativeLogCallback {
                override fun onLogLine(line: String, isError: Boolean) {
                    capturedLogs += line
                    if (isError) consoleLogger.error("GameJVM", line) else consoleLogger.info("GameJVM", line)
                }
            },
        )

        step(15, "Capturing final process logs (exit=$exitCode)...")
        if (exitCode != 0) {
            step(16, "Handling JVM exit / crash diagnostics...")
            val category = when {
                jvmProbe.contains("DLOPEN_FAILED", ignoreCase = true) || exitCode == -102 ->
                    LaunchErrorCategory.UNSUPPORTED_ARCHITECTURE
                exitCode == -105 || exitCode == -106 ->
                    LaunchErrorCategory.MISSING_LIBRARIES
                else -> null
            }
            fail(
                install,
                category ?: LaunchErrorCategory.FAILED_GAME_LAUNCH,
                "JVM exited with code $exitCode. Probe: $jvmProbe",
                code = exitCode,
            )
        } else {
            _state.value = LaunchPipelineState(
                isLaunching = false,
                currentStep = 16,
                stepTitle = "Session ended cleanly (exit 0)",
                activeInstallationName = install.name,
                activeInstallationId = install.id,
            )
        }
    }

    fun buildCompatibleJvmOptions(
        install: InstallationEntity,
        runtime: RuntimeEntity,
        classpathStr: String,
        nativesDir: File,
        appNativeLibDir: String,
    ): List<String> {
        val jvmOptions = mutableListOf<String>()
        jvmOptions += "-Xms${(install.memoryMb / 2).coerceAtLeast(256)}M"
        jvmOptions += "-Xmx${install.memoryMb}M"
        jvmOptions += "-Djava.home=${runtime.installPath}"
        jvmOptions += "-Djava.class.path=$classpathStr"
        val libPathParts = listOfNotNull(
            nativesDir.absolutePath,
            appNativeLibDir.takeIf { it.isNotBlank() },
            File(runtime.installPath, "lib").takeIf { it.exists() }?.absolutePath,
        )
        jvmOptions += "-Djava.library.path=${libPathParts.joinToString(":")}"
        jvmOptions += "-Dorg.lwjgl.librarypath=${nativesDir.absolutePath}"
        jvmOptions += "-Dos.name=Linux"
        jvmOptions += "-Duser.home=${install.gameDirPath}"
        jvmOptions += "-Dminecraft.launcher.brand=noxer-launcher"
        jvmOptions += "-Dminecraft.launcher.version=1.0.0"

        if (runtime.majorVersion >= 17) {
            jvmOptions += "--add-opens=java.base/java.lang=ALL-UNNAMED"
            jvmOptions += "--add-opens=java.base/java.util=ALL-UNNAMED"
        }

        val rawCustom = install.jvmArgs.split(Regex("\\s+")).filter { it.isNotBlank() }
        for (arg in rawCustom) {
            if (runtime.majorVersion <= 8 && (arg.startsWith("--add-opens") || arg.startsWith("--add-exports"))) {
                continue
            }
            jvmOptions += arg
        }
        return jvmOptions.distinct()
    }

    fun buildGameArguments(
        details: VersionDetails,
        username: String,
        uuid: String,
        accessToken: String,
        userType: String,
        gameDir: File,
        assetsDir: File,
        width: Int,
        height: Int,
    ): List<String> {
        val tokens = mapOf(
            "\${auth_player_name}" to username,
            "\${version_name}" to details.id,
            "\${game_directory}" to gameDir.absolutePath,
            "\${assets_root}" to assetsDir.absolutePath,
            "\${assets_index_name}" to (details.assetIndex?.id ?: details.assets ?: "legacy"),
            "\${auth_uuid}" to uuid.replace("-", ""),
            "\${auth_access_token}" to accessToken,
            "\${clientid}" to "noxer_client",
            "\${auth_xuid}" to "0",
            "\${user_type}" to userType,
            "\${version_type}" to details.type,
            "\${resolution_width}" to width.toString(),
            "\${resolution_height}" to height.toString(),
        )

        fun substitute(arg: String): String {
            var out = arg
            tokens.forEach { (k, v) -> out = out.replace(k, v) }
            return out
        }

        val legacy = details.minecraftArguments
        if (!legacy.isNullOrBlank()) {
            return legacy.split(Regex("\\s+")).filter { it.isNotBlank() }.map(::substitute)
        }

        val modernArgs = mutableListOf<String>()
        details.arguments?.game?.forEach { elem ->
            if (elem is JsonPrimitive && elem.isString) {
                elem.contentOrNull?.let { modernArgs += substitute(it) }
            }
        }
        if (modernArgs.isEmpty()) {
            return listOf(
                "--username", username,
                "--version", details.id,
                "--gameDir", gameDir.absolutePath,
                "--assetsDir", assetsDir.absolutePath,
                "--assetIndex", details.assetIndex?.id ?: details.assets ?: "legacy",
                "--uuid", uuid.replace("-", ""),
                "--accessToken", accessToken,
                "--userType", userType,
                "--versionType", details.type,
                "--width", width.toString(),
                "--height", height.toString(),
            )
        }
        return modernArgs
    }
}
