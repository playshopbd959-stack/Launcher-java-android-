package com.example.noxer.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.noxer.NoxerApplication
import com.example.noxer.auth.ActiveIdentity
import com.example.noxer.auth.AuthFlowState
import com.example.noxer.data.db.AccountEntity
import com.example.noxer.data.db.ConsoleLogEntity
import com.example.noxer.data.db.ControlProfileEntity
import com.example.noxer.data.db.CrashReportEntity
import com.example.noxer.data.db.InstallationEntity
import com.example.noxer.data.db.LoaderType
import com.example.noxer.data.db.OfflineProfileEntity
import com.example.noxer.data.db.RuntimeEntity
import com.example.noxer.download.DownloadProgressState
import com.example.noxer.graphics.GraphicsDiagnostics
import com.example.noxer.graphics.LivePerformanceMetrics
import com.example.noxer.input.ConnectedInputDevices
import com.example.noxer.input.KeybindMapping
import com.example.noxer.input.TouchButtonConfig
import com.example.noxer.installation.CreateInstallationRequest
import com.example.noxer.launch.LaunchPipelineState
import com.example.noxer.mods.InstalledModInfo
import com.example.noxer.mods.LoaderVersionOption
import com.example.noxer.runtime.AndroidAbiInfo
import com.example.noxer.runtime.RuntimeDiagnostics
import com.example.noxer.runtime.RuntimeInstallProgress
import com.example.noxer.settings.LauncherSettings
import com.example.noxer.storage.DirectoryItemInfo
import com.example.noxer.storage.StorageBreakdown
import com.example.noxer.version.LatestVersions
import com.example.noxer.version.VersionCategory
import com.example.noxer.version.VersionCenterRepository
import com.example.noxer.version.VersionDetails
import com.example.noxer.version.VersionSortOrder
import com.example.noxer.version.VersionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class VersionDetailSheetState(
    val summary: VersionSummary,
    val isLoading: Boolean = true,
    val details: VersionDetails? = null,
    val compatibleLoaders: Map<LoaderType, List<LoaderVersionOption>> = emptyMap(),
    val error: String? = null,
)

class NoxerViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as NoxerApplication).container

    // Core reactive streams
    val installations: StateFlow<List<InstallationEntity>> = container.installationManager.installations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accounts: StateFlow<List<AccountEntity>> = container.authManager.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeAccount: StateFlow<AccountEntity?> = container.authManager.activeAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val offlineProfiles: StateFlow<List<OfflineProfileEntity>> = container.offlineProfileManager.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeIdentity: StateFlow<ActiveIdentity> = container.accountSessionManager.activeIdentity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ActiveIdentity.None)

    val authFlowState: StateFlow<AuthFlowState> = container.authManager.authState

    val runtimes: StateFlow<List<RuntimeEntity>> = container.runtimeManager.runtimes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val runtimeInstallProgress: StateFlow<RuntimeInstallProgress> = container.runtimeManager.installProgress

    val controlProfiles: StateFlow<List<ControlProfileEntity>> = container.inputManager.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectedDevices: StateFlow<ConnectedInputDevices> = container.inputManager.connectedDevices

    val settings: StateFlow<LauncherSettings> = container.settingsManager.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LauncherSettings())

    val downloadProgress: StateFlow<DownloadProgressState> = container.downloadEngine.progress
    val launchState: StateFlow<LaunchPipelineState> = container.gameLauncher.state
    val graphicsDiagnostics: StateFlow<GraphicsDiagnostics> = container.rendererManager.diagnostics
    val liveMetrics: StateFlow<LivePerformanceMetrics> = container.rendererManager.metrics
    val consoleLogs: StateFlow<List<ConsoleLogEntity>> = container.consoleLogger.logs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val crashReports: StateFlow<List<CrashReportEntity>> = container.crashHandler.crashReports
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Version Center filters & state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow(VersionCategory.ALL_RELEASES)
    val selectedCategory: StateFlow<VersionCategory> = _selectedCategory.asStateFlow()

    private val _sortOrder = MutableStateFlow(VersionSortOrder.NEWEST_FIRST)
    val sortOrder: StateFlow<VersionSortOrder> = _sortOrder.asStateFlow()

    val isSyncingManifest: StateFlow<Boolean> = container.versionRepository.isSyncing
    val manifestSyncError: StateFlow<String?> = container.versionRepository.syncError
    val latestVersions: StateFlow<LatestVersions> = container.versionRepository.latestIds
    val installedVersionIds: StateFlow<Set<String>> = container.versionRepository.installedVersionIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val filteredVersions: StateFlow<List<VersionSummary>> = combine(
        container.versionRepository.versions,
        _searchQuery,
        _selectedCategory,
        _sortOrder,
        latestVersions,
        installedVersionIds,
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        val all = arr[0] as List<VersionSummary>
        val query = (arr[1] as String).trim()
        val category = arr[2] as VersionCategory
        val sort = arr[3] as VersionSortOrder
        val latest = arr[4] as LatestVersions
        @Suppress("UNCHECKED_CAST")
        val installed = arr[5] as Set<String>

        val filtered = all.filter { v ->
            val catMatch = if (query.isNotBlank() && category == VersionCategory.LATEST_RELEASE) {
                true
            } else {
                VersionCenterRepository.matchesCategory(v, category, latest.release, installed)
            }
            val queryMatch = query.isBlank() ||
                v.id.equals(query, ignoreCase = true) ||
                v.id.contains(query, ignoreCase = true) ||
                v.type.contains(query, ignoreCase = true)
            catMatch && queryMatch
        }
        when (sort) {
            VersionSortOrder.NEWEST_FIRST -> filtered
            VersionSortOrder.OLDEST_FIRST -> filtered.reversed()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _versionDetailSheet = MutableStateFlow<VersionDetailSheetState?>(null)
    val versionDetailSheet: StateFlow<VersionDetailSheetState?> = _versionDetailSheet.asStateFlow()

    // Mod Manager state
    private val _selectedModInstanceId = MutableStateFlow<String?>(null)
    val selectedModInstanceId: StateFlow<String?> = _selectedModInstanceId.asStateFlow()

    private val _instanceMods = MutableStateFlow<List<InstalledModInfo>>(emptyList())
    val instanceMods: StateFlow<List<InstalledModInfo>> = _instanceMods.asStateFlow()

    private val _compatibleLoadersForModTab = MutableStateFlow<Map<LoaderType, List<LoaderVersionOption>>>(emptyMap())
    val compatibleLoadersForModTab: StateFlow<Map<LoaderType, List<LoaderVersionOption>>> = _compatibleLoadersForModTab.asStateFlow()

    // Storage & Directory Browser state
    private val _storageBreakdown = MutableStateFlow<StorageBreakdown?>(null)
    val storageBreakdown: StateFlow<StorageBreakdown?> = _storageBreakdown.asStateFlow()

    private val _browsingDir = MutableStateFlow<File?>(null)
    val browsingDir: StateFlow<File?> = _browsingDir.asStateFlow()

    private val _directoryItems = MutableStateFlow<List<DirectoryItemInfo>>(emptyList())
    val directoryItems: StateFlow<List<DirectoryItemInfo>> = _directoryItems.asStateFlow()

    private val _uiToast = MutableStateFlow<String?>(null)
    val uiToast: StateFlow<String?> = _uiToast.asStateFlow()

    init {
        syncManifest(force = false)
        refreshStorageAndDevices()
    }

    fun clearToast() {
        _uiToast.value = null
    }

    fun syncManifest(force: Boolean = true) {
        viewModelScope.launch {
            container.versionRepository.syncManifest(forceRefresh = force)
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setCategory(category: VersionCategory) {
        _selectedCategory.value = category
    }

    fun setSortOrder(order: VersionSortOrder) {
        _sortOrder.value = order
    }

    fun openVersionDetails(summary: VersionSummary) {
        _versionDetailSheet.value = VersionDetailSheetState(summary = summary, isLoading = true)
        viewModelScope.launch {
            val detailsRes = container.versionRepository.getOrFetchVersionDetails(summary)
            val loaders = container.modLoaderManager.fetchCompatibleLoaders(summary.id)
            _versionDetailSheet.value = VersionDetailSheetState(
                summary = summary,
                isLoading = false,
                details = detailsRes.getOrNull(),
                compatibleLoaders = loaders,
                error = detailsRes.exceptionOrNull()?.message,
            )
        }
    }

    fun closeVersionDetails() {
        _versionDetailSheet.value = null
    }

    suspend fun checkDuplicateInstallationName(name: String): Boolean =
        container.installationManager.checkNameExists(name) != null

    fun createAndInstall(request: CreateInstallationRequest, onComplete: (Result<InstallationEntity>) -> Unit = {}) {
        viewModelScope.launch {
            val result = container.installationManager.createAndInstall(request)
            result.onSuccess { entity ->
                container.settingsManager.updateSelectedInstallation(entity.id)
                _uiToast.value = "Installation '${entity.name}' ready"
            }.onFailure { err ->
                _uiToast.value = err.message ?: "Installation failed"
            }
            onComplete(result)
        }
    }

    fun updateInstallation(entity: InstallationEntity) {
        viewModelScope.launch {
            container.installationManager.updateConfiguration(entity)
            _uiToast.value = "Saved '${entity.name}'"
        }
    }

    fun selectInstallation(id: String) {
        viewModelScope.launch {
            container.accountSessionManager.updateSelectedInstallationForCurrentIdentity(id)
        }
    }

    fun playInstallation(id: String) {
        container.gameLauncher.launchInstallation(id)
    }

    fun dismissLaunchError() {
        container.gameLauncher.clearError()
    }

    fun duplicateInstallation(entity: InstallationEntity) {
        viewModelScope.launch {
            val res = container.installationManager.duplicateInstallation(entity)
            _uiToast.value = res.fold(
                onSuccess = { "Created independent copy '${it.name}'" },
                onFailure = { "Duplicate failed: ${it.message}" },
            )
        }
    }

    fun repairInstallation(entity: InstallationEntity) {
        viewModelScope.launch {
            val manifestList = container.versionRepository.versions
            var summary: VersionSummary? = null
            // Look up in current filtered or cached list
            summary = filteredVersions.value.firstOrNull { it.id == entity.minecraftVersionId }
                ?: VersionSummary(
                    id = entity.minecraftVersionId,
                    type = "release",
                    url = "",
                    time = "",
                    releaseTime = "",
                    sha1 = "",
                )
            if (summary.url.isBlank()) {
                val synced = container.versionRepository.syncManifest(false).getOrNull()
                summary = synced?.versions?.firstOrNull { it.id == entity.minecraftVersionId } ?: summary
            }
            val res = container.installationManager.repairInstallation(
                entity = entity,
                versionSummary = summary,
                concurrency = settings.value.downloadConcurrency,
            )
            _uiToast.value = res.fold(
                onSuccess = { "Repaired '${it.name}' (${it.verifiedFileCount}/${it.totalFileCount} verified)" },
                onFailure = { "Repair failed: ${it.message}" },
            )
        }
    }

    fun deleteInstallation(entity: InstallationEntity) {
        viewModelScope.launch {
            container.installationManager.deleteInstallation(entity)
            if (settings.value.selectedInstallationId == entity.id) {
                container.settingsManager.updateSelectedInstallation(null)
            }
            _uiToast.value = "Deleted '${entity.name}'"
        }
    }

    fun cancelDownloads() {
        container.downloadEngine.requestCancel()
    }

    // Accounts & Offline Profiles
    fun startMicrosoftLogin() {
        container.authManager.startWebSignIn()
    }

    fun startDeviceCodeLogin() {
        viewModelScope.launch {
            val res = container.authManager.startDeviceCodeLogin()
            res.onSuccess { account ->
                container.accountSessionManager.onMicrosoftAuthenticated(account)
            }
        }
    }

    fun submitMicrosoftAuthCode(code: String) {
        viewModelScope.launch {
            val res = container.authManager.completeWebAuthCode(code)
            res.onSuccess { account ->
                container.accountSessionManager.onMicrosoftAuthenticated(account)
                _uiToast.value = "Verified Microsoft account '${account.username}'"
            }
        }
    }

    fun failMicrosoftAuth(reason: String) {
        container.authManager.failWebSignIn(reason)
    }

    fun resetAuthFlow() = container.authManager.resetAuthFlow()

    fun switchAccount(uuid: String) {
        viewModelScope.launch {
            container.accountSessionManager.selectMicrosoftAccount(uuid)
            _uiToast.value = "Selected Microsoft account"
        }
    }

    fun refreshActiveAccount() {
        viewModelScope.launch {
            val res = container.authManager.refreshActiveAccount()
            _uiToast.value = res.fold(
                onSuccess = { "Refreshed ${it.username}" },
                onFailure = { "Refresh failed: ${it.message}" },
            )
        }
    }

    fun logoutAccount(uuid: String) {
        viewModelScope.launch {
            container.accountSessionManager.logoutMicrosoftAccount(uuid)
            _uiToast.value = "Signed out Microsoft account"
        }
    }

    fun generatePreviewOfflineUuid(): String = container.offlineProfileManager.generateLocalProfileUuid()

    fun createOfflineProfile(
        username: String,
        displayName: String,
        iconId: String,
        notes: String,
        onResult: (Result<OfflineProfileEntity>) -> Unit = {},
    ) {
        viewModelScope.launch {
            val res = container.offlineProfileManager.createProfile(
                username = username,
                displayName = displayName,
                iconId = iconId,
                notes = notes,
            )
            res.onSuccess { created ->
                container.accountSessionManager.selectOfflineProfile(created.id)
                _uiToast.value = "Created offline profile '${created.username}'"
            }.onFailure { err ->
                _uiToast.value = err.message ?: "Could not create profile"
            }
            onResult(res)
        }
    }

    fun updateOfflineProfile(
        profileId: String,
        username: String,
        displayName: String,
        iconId: String,
        notes: String,
        onResult: (Result<OfflineProfileEntity>) -> Unit = {},
    ) {
        viewModelScope.launch {
            val res = container.offlineProfileManager.updateProfile(
                profileId = profileId,
                username = username,
                displayName = displayName,
                iconId = iconId,
                notes = notes,
            )
            res.onSuccess { updated ->
                _uiToast.value = "Updated offline profile '${updated.username}'"
            }.onFailure { err ->
                _uiToast.value = err.message ?: "Could not update profile"
            }
            onResult(res)
        }
    }

    fun selectOfflineProfile(profileId: String) {
        viewModelScope.launch {
            container.accountSessionManager.selectOfflineProfile(profileId)
            _uiToast.value = "Selected offline profile"
        }
    }

    fun deleteOfflineProfile(profileId: String) {
        viewModelScope.launch {
            container.accountSessionManager.deleteOfflineProfile(profileId)
            _uiToast.value = "Deleted offline profile"
        }
    }

    fun clearActiveSelection() {
        viewModelScope.launch {
            container.accountSessionManager.clearActiveSelection()
        }
    }

    // Java Runtimes
    fun installJavaRuntime(majorVersion: Int) {
        viewModelScope.launch {
            val res = container.runtimeManager.fetchAndInstallRuntime(majorVersion)
            _uiToast.value = res.fold(
                onSuccess = { "Verified & installed ${it.displayName}" },
                onFailure = { "Java $majorVersion install failed: ${it.message}" },
            )
            refreshStorageAndDevices()
        }
    }

    fun installAndContinueLaunch(majorVersion: Int, installationId: String?) {
        viewModelScope.launch {
            container.gameLauncher.clearError()
            val res = container.runtimeManager.fetchAndInstallRuntime(majorVersion)
            if (res.isSuccess) {
                _uiToast.value = "Verified Java $majorVersion — continuing launch"
                if (!installationId.isNullOrBlank()) {
                    container.gameLauncher.launchInstallation(installationId)
                }
            } else {
                _uiToast.value = "Java $majorVersion install failed: ${res.exceptionOrNull()?.message}"
            }
            refreshStorageAndDevices()
        }
    }

    fun cancelJavaRuntimeInstall() {
        container.runtimeManager.cancelInstall()
    }

    fun updateJavaRuntime(runtime: RuntimeEntity) {
        viewModelScope.launch {
            val res = container.runtimeManager.updateRuntime(runtime)
            _uiToast.value = res.fold(
                onSuccess = { "Updated & verified ${it.displayName}" },
                onFailure = { "Update failed: ${it.message}" },
            )
            refreshStorageAndDevices()
        }
    }

    fun verifyJavaRuntime(runtime: RuntimeEntity) {
        viewModelScope.launch {
            val updated = container.runtimeManager.verifyRuntime(runtime)
            _uiToast.value = updated.diagnosticMessage
        }
    }

    fun setDefaultJavaRuntime(runtime: RuntimeEntity) {
        viewModelScope.launch {
            container.runtimeManager.setDefaultRuntime(runtime)
            _uiToast.value = "Default runtime set to ${runtime.displayName}"
        }
    }

    fun deleteJavaRuntime(runtime: RuntimeEntity) {
        viewModelScope.launch {
            container.runtimeManager.deleteRuntime(runtime)
            _uiToast.value = "Removed ${runtime.displayName}"
            refreshStorageAndDevices()
        }
    }

    fun getRuntimeHostDiagnostics(): RuntimeDiagnostics = container.runtimeManager.getHostDiagnostics()

    fun getRuntimeAbiInfo(): AndroidAbiInfo = container.runtimeManager.detectAbiInfo()

    fun getRuntimeAvailableBytes(): Long =
        runCatching { android.os.StatFs(container.paths.runtimesDir.absolutePath).availableBytes }.getOrDefault(0L)

    // Mods & Loaders
    fun selectModInstance(instance: InstallationEntity) {
        _selectedModInstanceId.value = instance.id
        viewModelScope.launch {
            val loader = runCatching { LoaderType.valueOf(instance.loaderType) }.getOrDefault(LoaderType.VANILLA)
            _instanceMods.value = container.modLoaderManager.scanInstanceMods(instance.id, loader)
            _compatibleLoadersForModTab.value = container.modLoaderManager.fetchCompatibleLoaders(instance.minecraftVersionId)
        }
    }

    fun installFabricOnInstance(instance: InstallationEntity, loaderVersion: String) {
        viewModelScope.launch {
            val res = container.modLoaderManager.installFabricProfile(instance.id, instance.minecraftVersionId, loaderVersion)
            if (res.isSuccess) {
                container.installationManager.updateConfiguration(
                    instance.copy(loaderType = LoaderType.FABRIC.name, loaderVersion = loaderVersion)
                )
                _uiToast.value = "Installed Fabric $loaderVersion on '${instance.name}'"
                selectModInstance(instance.copy(loaderType = LoaderType.FABRIC.name, loaderVersion = loaderVersion))
            } else {
                _uiToast.value = "Fabric install failed: ${res.exceptionOrNull()?.message}"
            }
        }
    }

    fun toggleMod(instance: InstallationEntity, mod: InstalledModInfo) {
        viewModelScope.launch {
            container.modLoaderManager.toggleModEnabled(mod)
            selectModInstance(instance)
        }
    }

    fun deleteMod(instance: InstallationEntity, mod: InstalledModInfo) {
        viewModelScope.launch {
            container.modLoaderManager.deleteMod(mod)
            selectModInstance(instance)
        }
    }

    fun importModJar(instance: InstallationEntity, uri: Uri, displayName: String) {
        viewModelScope.launch {
            val cleanName = displayName.ifBlank { "imported_mod_${System.currentTimeMillis()}.jar" }
                .let { if (it.endsWith(".jar", ignoreCase = true)) it else "$it.jar" }
            val dest = File(File(instance.gameDirPath, "mods"), cleanName)
            val res = container.storageManager.copyUriToFile(uri, dest)
            _uiToast.value = res.fold(
                onSuccess = {
                    selectModInstance(instance)
                    "Added mod ${it.name}"
                },
                onFailure = { "Import failed: ${it.message}" },
            )
        }
    }

    fun importMrpack(instance: InstallationEntity, uri: Uri) {
        viewModelScope.launch {
            val tmp = File(getApplication<Application>().cacheDir, "import_${System.currentTimeMillis()}.mrpack")
            val copied = container.storageManager.copyUriToFile(uri, tmp)
            if (copied.isFailure) {
                _uiToast.value = "Could not read .mrpack file"
                return@launch
            }
            val res = container.modLoaderManager.importMrpackFile(instance.id, tmp)
            tmp.delete()
            _uiToast.value = res.fold(
                onSuccess = {
                    selectModInstance(instance)
                    "Imported $it"
                },
                onFailure = { "Modpack import failed: ${it.message}" },
            )
        }
    }

    // Controls
    fun decodeTouchButtons(raw: String): List<TouchButtonConfig> = container.inputManager.decodeTouchButtons(raw)
    fun decodeKeybinds(raw: String): List<KeybindMapping> = container.inputManager.decodeKeybinds(raw)

    fun saveControlProfile(
        profile: ControlProfileEntity,
        buttons: List<TouchButtonConfig>,
        keybinds: List<KeybindMapping>,
        sensitivity: Float,
        invertY: Boolean,
    ) {
        viewModelScope.launch {
            container.inputManager.saveProfile(profile, buttons, keybinds, sensitivity, invertY)
            _uiToast.value = "Saved control profile '${profile.name}'"
        }
    }

    fun createControlProfile(name: String) {
        viewModelScope.launch {
            val created = container.inputManager.createNewProfile(name)
            _uiToast.value = "Created profile '${created.name}'"
        }
    }

    fun deleteControlProfile(profile: ControlProfileEntity) {
        viewModelScope.launch {
            container.inputManager.deleteProfile(profile)
        }
    }

    // Settings, Storage & Directory Browser
    fun updateSettings(transform: (LauncherSettings) -> LauncherSettings) {
        viewModelScope.launch {
            container.settingsManager.updateSettings(transform)
        }
    }

    fun refreshStorageAndDevices() {
        viewModelScope.launch {
            _storageBreakdown.value = container.storageManager.computeBreakdown()
            container.inputManager.refreshConnectedDevices()
            container.rendererManager.refreshDiagnostics()
            container.rendererManager.sampleMemorySnapshot("Ready")
        }
    }

    fun openDirectoryBrowser(dir: File) {
        _browsingDir.value = dir
        viewModelScope.launch {
            _directoryItems.value = container.storageManager.listDirectory(dir)
        }
    }

    fun closeDirectoryBrowser() {
        _browsingDir.value = null
    }

    fun exportDirectoryToSaf(dir: File, targetUri: Uri) {
        viewModelScope.launch {
            val res = container.storageManager.backupDirectoryToUri(dir, targetUri)
            _uiToast.value = res.fold(
                onSuccess = { "Backed up $it files to ZIP" },
                onFailure = { "Backup failed: ${it.message}" },
            )
            refreshStorageAndDevices()
        }
    }

    fun restoreZipFromSaf(uri: Uri, targetDir: File) {
        viewModelScope.launch {
            val res = container.storageManager.restoreZipFromUri(uri, targetDir)
            _uiToast.value = res.fold(
                onSuccess = { "Restored $it files into ${targetDir.name}" },
                onFailure = { "Restore failed: ${it.message}" },
            )
            refreshStorageAndDevices()
        }
    }

    fun exportLogsToSaf(uri: Uri) {
        viewModelScope.launch {
            val logFile = container.consoleLogger.getLatestLogFile()
            if (!logFile.exists()) {
                _uiToast.value = "No log file recorded yet"
                return@launch
            }
            val res = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    logFile.inputStream().use { it.copyTo(out) }
                }
            }
            _uiToast.value = if (res.isSuccess) "Exported latest.log" else "Export failed"
        }
    }

    fun clearConsoleLogs() {
        viewModelScope.launch {
            container.consoleLogger.clear()
        }
    }

    fun deleteCrashReport(id: String) {
        viewModelScope.launch {
            container.crashHandler.deleteReport(id)
        }
    }

    suspend fun fetchCompatibleLoaders(mcVersion: String): Map<LoaderType, List<LoaderVersionOption>> =
        container.modLoaderManager.fetchCompatibleLoaders(mcVersion)

    fun readFilePreview(file: File): String = runCatching {
        if (file.length() > 128 * 1024) file.readText().take(16 * 1024) + "\n... [truncated]"
        else file.readText()
    }.getOrElse { "Cannot preview binary or unreadable file: ${it.message}" }
}
