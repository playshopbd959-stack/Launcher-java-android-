package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.noxer.NoxerApplication
import com.example.noxer.ui.NoxerViewModel
import com.example.noxer.ui.components.DirectoryBrowserDialog
import com.example.noxer.ui.components.LaunchStatusDialog
import com.example.noxer.ui.screens.AccountsScreen
import com.example.noxer.ui.screens.ConsoleScreen
import com.example.noxer.ui.screens.ControlsScreen
import com.example.noxer.ui.screens.CreateInstallScreen
import com.example.noxer.ui.screens.CreateOfflineProfileScreen
import com.example.noxer.ui.screens.HomeScreen
import com.example.noxer.ui.screens.InstallationsScreen
import com.example.noxer.ui.screens.ModsScreen
import com.example.noxer.ui.screens.SettingsScreen
import com.example.noxer.ui.screens.VersionCenterScreen
import com.example.noxer.version.VersionSummary
import com.example.ui.theme.NoxerBlack
import com.example.ui.theme.NoxerBorder
import com.example.ui.theme.NoxerLauncherTheme
import com.example.ui.theme.NoxerNeonRed
import com.example.ui.theme.NoxerObsidian
import com.example.ui.theme.NoxerSurface
import com.example.ui.theme.NoxerTextMuted
import com.example.ui.theme.NoxerTextPrimary
import kotlinx.serialization.Serializable
import java.io.File

@Serializable object HomeRoute
@Serializable object VersionsRoute
@Serializable object InstallationsRoute
@Serializable object AccountsRoute
@Serializable object ModsRoute
@Serializable object ControlsRoute
@Serializable object SettingsRoute
@Serializable object ConsoleRoute
@Serializable data class CreateInstallRoute(
    val versionId: String,
    val versionType: String,
    val versionUrl: String,
    val versionTime: String,
    val versionReleaseTime: String,
    val versionSha1: String,
    val editingInstallationId: String? = null,
)
@Serializable data class CreateOfflineProfileRoute(
    val editingProfileId: String? = null,
)

private data class NavDest(
    val route: Any,
    val label: String,
    val icon: ImageVector,
    val tag: String,
    val isPrimaryBottom: Boolean,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NoxerLauncherTheme {
                NoxerAppShell()
            }
        }
    }
}

@Composable
fun NoxerAppShell(vm: NoxerViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val installations by vm.installations.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val offlineProfiles by vm.offlineProfiles.collectAsStateWithLifecycle()
    val activeIdentity by vm.activeIdentity.collectAsStateWithLifecycle()
    val authFlowState by vm.authFlowState.collectAsStateWithLifecycle()
    val runtimes by vm.runtimes.collectAsStateWithLifecycle()
    val runtimeProgress by vm.runtimeInstallProgress.collectAsStateWithLifecycle()
    val controlProfiles by vm.controlProfiles.collectAsStateWithLifecycle()
    val connectedDevices by vm.connectedDevices.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val downloadProgress by vm.downloadProgress.collectAsStateWithLifecycle()
    val launchState by vm.launchState.collectAsStateWithLifecycle()
    val graphicsDiag by vm.graphicsDiagnostics.collectAsStateWithLifecycle()
    val liveMetrics by vm.liveMetrics.collectAsStateWithLifecycle()
    val consoleLogs by vm.consoleLogs.collectAsStateWithLifecycle()
    val crashReports by vm.crashReports.collectAsStateWithLifecycle()

    val versions by vm.filteredVersions.collectAsStateWithLifecycle()
    val installedVersionIds by vm.installedVersionIds.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val selectedCategory by vm.selectedCategory.collectAsStateWithLifecycle()
    val sortOrder by vm.sortOrder.collectAsStateWithLifecycle()
    val isSyncing by vm.isSyncingManifest.collectAsStateWithLifecycle()
    val syncError by vm.manifestSyncError.collectAsStateWithLifecycle()
    val versionDetailSheet by vm.versionDetailSheet.collectAsStateWithLifecycle()
    val latestVersions by vm.latestVersions.collectAsStateWithLifecycle()

    val selectedModInstanceId by vm.selectedModInstanceId.collectAsStateWithLifecycle()
    val instanceMods by vm.instanceMods.collectAsStateWithLifecycle()
    val compatibleLoaders by vm.compatibleLoadersForModTab.collectAsStateWithLifecycle()

    val storageBreakdown by vm.storageBreakdown.collectAsStateWithLifecycle()
    val browsingDir by vm.browsingDir.collectAsStateWithLifecycle()
    val directoryItems by vm.directoryItems.collectAsStateWithLifecycle()
    val uiToast by vm.uiToast.collectAsStateWithLifecycle()

    val paths = (androidx.compose.ui.platform.LocalContext.current.applicationContext as NoxerApplication).container.paths

    var pendingExportDir by remember { mutableStateOf<File?>(null) }
    val exportDirZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val dir = pendingExportDir
        if (uri != null && dir != null) {
            vm.exportDirectoryToSaf(dir, uri)
        }
        pendingExportDir = null
    }

    LaunchedEffect(uiToast) {
        uiToast?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            vm.clearToast()
        }
    }

    val destinations = remember {
        listOf(
            NavDest(HomeRoute, "Home", Icons.Default.Home, "nav_home", true),
            NavDest(VersionsRoute, "Versions", Icons.Default.Explore, "nav_versions", true),
            NavDest(InstallationsRoute, "Library", Icons.Default.Apps, "nav_installations", true),
            NavDest(ModsRoute, "Mods", Icons.Default.Extension, "nav_mods", true),
            NavDest(SettingsRoute, "Settings", Icons.Default.Settings, "nav_settings", true),
            NavDest(AccountsRoute, "Accounts", Icons.Default.AccountCircle, "nav_accounts", false),
            NavDest(ControlsRoute, "Controls", Icons.Default.Gamepad, "nav_controls", false),
            NavDest(ConsoleRoute, "Console", Icons.Default.Terminal, "nav_console", false),
        )
    }

    fun navigateTop(route: Any) {
        navController.navigate(route) {
            popUpTo(HomeRoute) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(NoxerBlack)) {
        val isCompact = maxWidth < 600.dp

        Scaffold(
            containerColor = NoxerBlack,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (isCompact) {
                    Surface(
                        color = NoxerObsidian,
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "NOXER LAUNCHER",
                                style = MaterialTheme.typography.titleMedium,
                                color = NoxerTextPrimary,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                IconButton(
                                    onClick = { navigateTop(AccountsRoute) },
                                    modifier = Modifier.testTag("top_accounts_button"),
                                ) {
                                    Icon(
                                        Icons.Default.AccountCircle,
                                        contentDescription = "Accounts",
                                        tint = if (backStackEntry?.destination?.hasRoute(AccountsRoute::class) == true) NoxerNeonRed else NoxerTextPrimary,
                                    )
                                }
                                IconButton(
                                    onClick = { navigateTop(ControlsRoute) },
                                    modifier = Modifier.testTag("top_controls_button"),
                                ) {
                                    Icon(
                                        Icons.Default.Gamepad,
                                        contentDescription = "Controls",
                                        tint = if (backStackEntry?.destination?.hasRoute(ControlsRoute::class) == true) NoxerNeonRed else NoxerTextPrimary,
                                    )
                                }
                                IconButton(
                                    onClick = { navigateTop(ConsoleRoute) },
                                    modifier = Modifier.testTag("top_console_button"),
                                ) {
                                    Icon(
                                        Icons.Default.Terminal,
                                        contentDescription = "Console",
                                        tint = if (backStackEntry?.destination?.hasRoute(ConsoleRoute::class) == true) NoxerNeonRed else NoxerTextPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            bottomBar = {
                if (isCompact) {
                    NavigationBar(
                        containerColor = NoxerObsidian,
                        modifier = Modifier.navigationBarsPadding(),
                    ) {
                        destinations.filter { it.isPrimaryBottom }.forEach { dest ->
                            val selected = backStackEntry?.destination?.hasRoute(dest.route::class) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTop(dest.route) },
                                icon = { Icon(dest.icon, contentDescription = dest.label, modifier = Modifier.size(22.dp)) },
                                label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.White,
                                    selectedTextColor = NoxerNeonRed,
                                    indicatorColor = NoxerNeonRed,
                                    unselectedIconColor = NoxerTextMuted,
                                    unselectedTextColor = NoxerTextMuted,
                                ),
                                modifier = Modifier.testTag(dest.tag),
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .let { if (!isCompact) it.statusBarsPadding().navigationBarsPadding() else it },
            ) {
                if (!isCompact) {
                    NavigationRail(
                        containerColor = NoxerObsidian,
                        modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
                    ) {
                        destinations.forEach { dest ->
                            val selected = backStackEntry?.destination?.hasRoute(dest.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTop(dest.route) },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = Color.White,
                                    selectedTextColor = NoxerNeonRed,
                                    indicatorColor = NoxerNeonRed,
                                    unselectedIconColor = NoxerTextMuted,
                                    unselectedTextColor = NoxerTextMuted,
                                ),
                                modifier = Modifier.testTag(dest.tag),
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    NavHost(
                        navController = navController,
                        startDestination = HomeRoute,
                    ) {
                        composable<HomeRoute> {
                            HomeScreen(
                                activeIdentity = activeIdentity,
                                installations = installations,
                                selectedInstallationId = settings.selectedInstallationId,
                                runtimes = runtimes,
                                latestVersions = latestVersions,
                                downloadProgress = downloadProgress,
                                metrics = liveMetrics,
                                onPlaySelected = { vm.playInstallation(it.id) },
                                onSelectInstallation = { vm.selectInstallation(it) },
                                onOpenVersionCenter = { navigateTop(VersionsRoute) },
                                onOpenAccounts = { navigateTop(AccountsRoute) },
                                onCancelDownload = { vm.cancelDownloads() },
                            )
                        }

                        composable<VersionsRoute> {
                            VersionCenterScreen(
                                versions = versions,
                                installedVersionIds = installedVersionIds,
                                searchQuery = searchQuery,
                                selectedCategory = selectedCategory,
                                sortOrder = sortOrder,
                                isSyncing = isSyncing,
                                syncError = syncError,
                                detailSheet = versionDetailSheet,
                                onSearchChange = vm::setSearchQuery,
                                onCategorySelect = vm::setCategory,
                                onToggleSort = {
                                    val next = if (sortOrder == com.example.noxer.version.VersionSortOrder.NEWEST_FIRST) {
                                        com.example.noxer.version.VersionSortOrder.OLDEST_FIRST
                                    } else {
                                        com.example.noxer.version.VersionSortOrder.NEWEST_FIRST
                                    }
                                    vm.setSortOrder(next)
                                },
                                onSyncManifest = { vm.syncManifest(true) },
                                onSelectVersionDetails = vm::openVersionDetails,
                                onDismissVersionDetails = vm::closeVersionDetails,
                                onCreateInstallation = { v ->
                                    navController.navigate(
                                        CreateInstallRoute(
                                            versionId = v.id,
                                            versionType = v.type,
                                            versionUrl = v.url,
                                            versionTime = v.time,
                                            versionReleaseTime = v.releaseTime,
                                            versionSha1 = v.sha1,
                                        )
                                    )
                                },
                            )
                        }

                        composable<CreateInstallRoute> { entry ->
                            val args = entry.toRoute<CreateInstallRoute>()
                            val editing = args.editingInstallationId?.let { eid ->
                                installations.firstOrNull { it.id == eid }
                            }
                            CreateInstallScreen(
                                targetVersion = VersionSummary(
                                    id = args.versionId,
                                    type = args.versionType,
                                    url = args.versionUrl,
                                    time = args.versionTime,
                                    releaseTime = args.versionReleaseTime,
                                    sha1 = args.versionSha1,
                                ),
                                editingExisting = editing,
                                runtimes = runtimes,
                                controlProfiles = controlProfiles,
                                defaultSettings = settings,
                                fetchCompatibleLoaders = vm::fetchCompatibleLoaders,
                                checkNameExists = vm::checkDuplicateInstallationName,
                                onCreateInstallation = { req ->
                                    vm.createAndInstall(req)
                                    navigateTop(InstallationsRoute)
                                },
                                onUpdateInstallation = { updated ->
                                    vm.updateInstallation(updated)
                                },
                                onBack = { navController.popBackStack() },
                            )
                        }

                        composable<InstallationsRoute> {
                            InstallationsScreen(
                                installations = installations,
                                downloadProgress = downloadProgress,
                                onPlay = { vm.playInstallation(it.id) },
                                onEdit = { inst ->
                                    navController.navigate(
                                        CreateInstallRoute(
                                            versionId = inst.minecraftVersionId,
                                            versionType = "release",
                                            versionUrl = "",
                                            versionTime = "",
                                            versionReleaseTime = "",
                                            versionSha1 = "",
                                            editingInstallationId = inst.id,
                                        )
                                    )
                                },
                                onDuplicate = vm::duplicateInstallation,
                                onRepair = vm::repairInstallation,
                                onDelete = vm::deleteInstallation,
                                onOpenDirectory = vm::openDirectoryBrowser,
                                onOpenVersionCenter = { navigateTop(VersionsRoute) },
                                onCancelDownload = vm::cancelDownloads,
                            )
                        }

                        composable<AccountsRoute> {
                            AccountsScreen(
                                activeIdentity = activeIdentity,
                                accounts = accounts,
                                offlineProfiles = offlineProfiles,
                                authFlowState = authFlowState,
                                onStartMicrosoftLogin = vm::startMicrosoftLogin,
                                onSubmitAuthCode = vm::submitMicrosoftAuthCode,
                                onFailAuth = vm::failMicrosoftAuth,
                                onCancelAuthFlow = vm::resetAuthFlow,
                                onSelectMicrosoftAccount = vm::switchAccount,
                                onRefreshActive = vm::refreshActiveAccount,
                                onLogoutMicrosoft = vm::logoutAccount,
                                onCreateOfflineProfile = {
                                    navController.navigate(CreateOfflineProfileRoute(editingProfileId = null))
                                },
                                onEditOfflineProfile = { profile ->
                                    navController.navigate(CreateOfflineProfileRoute(editingProfileId = profile.id))
                                },
                                onSelectOfflineProfile = vm::selectOfflineProfile,
                                onDeleteOfflineProfile = vm::deleteOfflineProfile,
                            )
                        }

                        composable<CreateOfflineProfileRoute> { entry ->
                            val args = entry.toRoute<CreateOfflineProfileRoute>()
                            val editingProfile = args.editingProfileId?.let { id ->
                                offlineProfiles.firstOrNull { it.id == id }
                            }
                            val previewUuid = remember(args.editingProfileId) { vm.generatePreviewOfflineUuid() }
                            CreateOfflineProfileScreen(
                                existingProfiles = offlineProfiles,
                                editingProfile = editingProfile,
                                generatedPreviewUuid = previewUuid,
                                onSaveNew = { username, displayName, iconId, notes, onDone ->
                                    vm.createOfflineProfile(username, displayName, iconId, notes, onDone)
                                },
                                onSaveEdit = { profileId, username, displayName, iconId, notes, onDone ->
                                    vm.updateOfflineProfile(profileId, username, displayName, iconId, notes, onDone)
                                },
                                onBack = { navController.popBackStack() },
                            )
                        }

                        composable<ModsRoute> {
                            ModsScreen(
                                installations = installations,
                                selectedInstanceId = selectedModInstanceId,
                                mods = instanceMods,
                                compatibleLoaders = compatibleLoaders,
                                onSelectInstance = vm::selectModInstance,
                                onInstallFabric = vm::installFabricOnInstance,
                                onToggleMod = vm::toggleMod,
                                onDeleteMod = vm::deleteMod,
                                onImportModJar = vm::importModJar,
                                onImportMrpack = vm::importMrpack,
                            )
                        }

                        composable<ControlsRoute> {
                            ControlsScreen(
                                profiles = controlProfiles,
                                connectedDevices = connectedDevices,
                                decodeTouchButtons = vm::decodeTouchButtons,
                                decodeKeybinds = vm::decodeKeybinds,
                                onSaveProfile = vm::saveControlProfile,
                                onCreateProfile = vm::createControlProfile,
                                onRefreshDevices = vm::refreshStorageAndDevices,
                            )
                        }

                        composable<SettingsRoute> {
                            SettingsScreen(
                                settings = settings,
                                runtimes = runtimes,
                                runtimeInstallProgress = runtimeProgress,
                                hostDiagnostics = vm.getRuntimeHostDiagnostics(),
                                graphicsDiagnostics = graphicsDiag,
                                storageBreakdown = storageBreakdown,
                                rootInstancesDir = paths.instancesDir,
                                onUpdateSettings = vm::updateSettings,
                                onInstallRuntime = vm::installJavaRuntime,
                                onVerifyRuntime = vm::verifyJavaRuntime,
                                onDeleteRuntime = vm::deleteJavaRuntime,
                                onExportBackupSaf = vm::exportDirectoryToSaf,
                                onRestoreBackupSaf = vm::restoreZipFromSaf,
                                onRefreshDiagnostics = vm::refreshStorageAndDevices,
                            )
                        }

                        composable<ConsoleRoute> {
                            ConsoleScreen(
                                logs = consoleLogs,
                                crashReports = crashReports,
                                onExportLogs = vm::exportLogsToSaf,
                                onClearLogs = vm::clearConsoleLogs,
                                onDeleteCrashReport = vm::deleteCrashReport,
                            )
                        }
                    }
                }
            }
        }

        LaunchStatusDialog(
            state = launchState,
            onDismiss = vm::dismissLaunchError,
        )

        browsingDir?.let { dir ->
            DirectoryBrowserDialog(
                currentDir = dir,
                rootBoundary = paths.instancesDir,
                items = directoryItems,
                onNavigate = vm::openDirectoryBrowser,
                onBackupToSaf = { target ->
                    pendingExportDir = target
                    exportDirZipLauncher.launch("${target.name}_backup.zip")
                },
                onReadPreview = vm::readFilePreview,
                onDismiss = vm::closeDirectoryBrowser,
            )
        }
    }
}
