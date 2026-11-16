package com.example.noxer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.noxer.data.db.ControlProfileEntity
import com.example.noxer.data.db.InstallationEntity
import com.example.noxer.data.db.LoaderType
import com.example.noxer.data.db.RuntimeEntity
import com.example.noxer.graphics.RendererBackend
import com.example.noxer.installation.CreateInstallationRequest
import com.example.noxer.mods.LoaderVersionOption
import com.example.noxer.settings.LauncherSettings
import com.example.noxer.ui.components.NoxerPanel
import com.example.noxer.version.VersionSummary
import com.example.ui.theme.NoxerAmber
import com.example.ui.theme.NoxerBlack
import com.example.ui.theme.NoxerBorder
import com.example.ui.theme.NoxerNeonRed
import com.example.ui.theme.NoxerSurface
import com.example.ui.theme.NoxerSurfaceElevated
import com.example.ui.theme.NoxerTextMuted
import com.example.ui.theme.NoxerTextPrimary
import com.example.ui.theme.NoxerTextSecondary
import kotlinx.coroutines.launch

@Composable
fun CreateInstallScreen(
    targetVersion: VersionSummary,
    editingExisting: InstallationEntity?,
    runtimes: List<RuntimeEntity>,
    controlProfiles: List<ControlProfileEntity>,
    defaultSettings: LauncherSettings,
    fetchCompatibleLoaders: suspend (String) -> Map<LoaderType, List<LoaderVersionOption>>,
    checkNameExists: suspend (String) -> Boolean,
    onCreateInstallation: (CreateInstallationRequest) -> Unit,
    onUpdateInstallation: (InstallationEntity) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(editingExisting?.name ?: "Minecraft ${targetVersion.id}") }
    var directorySlug by remember {
        mutableStateOf(
            editingExisting?.gameDirPath?.substringBeforeLast("/.minecraft")?.substringAfterLast("/")
                ?: "mc_${targetVersion.id.replace('.', '_')}"
        )
    }
    var selectedRuntimeId by remember { mutableStateOf(editingExisting?.javaRuntimeId ?: "auto") }
    var selectedLoader by remember {
        mutableStateOf(
            editingExisting?.let { runCatching { LoaderType.valueOf(it.loaderType) }.getOrNull() }
                ?: LoaderType.VANILLA
        )
    }
    var selectedLoaderVersion by remember { mutableStateOf(editingExisting?.loaderVersion) }
    var memoryMb by remember { mutableFloatStateOf((editingExisting?.memoryMb ?: defaultSettings.defaultMemoryMb).toFloat()) }
    var jvmArgs by remember { mutableStateOf(editingExisting?.jvmArgs ?: defaultSettings.defaultJvmArgs) }
    var resWidth by remember { mutableIntStateOf(editingExisting?.resolutionWidth ?: 1280) }
    var resHeight by remember { mutableIntStateOf(editingExisting?.resolutionHeight ?: 720) }
    var scalePercent by remember { mutableIntStateOf(editingExisting?.resolutionScalePercent ?: defaultSettings.resolutionScalePercent) }
    var fullscreen by remember { mutableStateOf(editingExisting?.fullscreen ?: defaultSettings.fullscreen) }
    var selectedRenderer by remember { mutableStateOf(editingExisting?.rendererId ?: defaultSettings.defaultRendererId) }
    var selectedControlProfileId by remember { mutableStateOf(editingExisting?.controlProfileId ?: "default_touch") }

    var compatibleLoaders by remember { mutableStateOf<Map<LoaderType, List<LoaderVersionOption>>>(mapOf(LoaderType.VANILLA to emptyList())) }
    var showOverwriteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(targetVersion.id) {
        compatibleLoaders = fetchCompatibleLoaders(targetVersion.id)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NoxerBlack)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = NoxerTextPrimary)
            }
            Column {
                Text(
                    text = if (editingExisting != null) "EDIT INSTALLATION" else "CREATE INSTALLATION",
                    style = MaterialTheme.typography.headlineSmall,
                    color = NoxerTextPrimary,
                )
                Text("Minecraft ${targetVersion.id} (${targetVersion.type})", style = MaterialTheme.typography.labelSmall, color = NoxerNeonRed)
            }
        }

        // 1 & 2 & 4: Name, Version, Game Directory
        NoxerPanel {
            Text("1. INSTANCE IDENTITY & DIRECTORY", style = MaterialTheme.typography.labelMedium, color = NoxerNeonRed)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Installation Name") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NoxerNeonRed,
                    unfocusedBorderColor = NoxerBorder,
                    focusedTextColor = NoxerTextPrimary,
                    unfocusedTextColor = NoxerTextPrimary,
                ),
                modifier = Modifier.fillMaxWidth().testTag("install_name_input"),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = directorySlug,
                onValueChange = { directorySlug = it },
                label = { Text("Independent Game Directory Name") },
                enabled = editingExisting == null,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NoxerNeonRed,
                    unfocusedBorderColor = NoxerBorder,
                    focusedTextColor = NoxerTextPrimary,
                    unfocusedTextColor = NoxerTextPrimary,
                ),
                modifier = Modifier.fillMaxWidth().testTag("install_dir_input"),
            )
        }

        // 3. Java Runtime
        NoxerPanel {
            Text("2. JAVA RUNTIME", style = MaterialTheme.typography.labelMedium, color = NoxerNeonRed)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedRuntimeId == "auto",
                    onClick = { selectedRuntimeId = "auto" },
                    label = { Text("Auto-Detect Compatible") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NoxerNeonRed, selectedLabelColor = Color.White),
                )
                runtimes.forEach { rt ->
                    val statusSuffix = if (rt.isVerified) " • Verified" else " • Unverified"
                    FilterChip(
                        selected = selectedRuntimeId == rt.id,
                        onClick = { selectedRuntimeId = rt.id },
                        label = { Text("Java ${rt.majorVersion} (${rt.abi})$statusSuffix") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NoxerNeonRed, selectedLabelColor = Color.White),
                        modifier = Modifier.testTag("install_runtime_chip_${rt.id}"),
                    )
                }
            }
        }

        // 5. Mod Loader (Only compatible loaders shown)
        NoxerPanel {
            Text("3. MOD LOADER (COMPATIBLE WITH ${targetVersion.id})", style = MaterialTheme.typography.labelMedium, color = NoxerNeonRed)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                compatibleLoaders.keys.forEach { loader ->
                    FilterChip(
                        selected = selectedLoader == loader,
                        onClick = {
                            selectedLoader = loader
                            selectedLoaderVersion = compatibleLoaders[loader]?.firstOrNull()?.version
                        },
                        label = { Text(loader.displayName) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NoxerNeonRed, selectedLabelColor = Color.White),
                    )
                }
            }
            val options = compatibleLoaders[selectedLoader].orEmpty()
            if (selectedLoader != LoaderType.VANILLA && options.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Loader Version:", style = MaterialTheme.typography.labelSmall, color = NoxerTextSecondary)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.take(8).forEach { opt ->
                        FilterChip(
                            selected = selectedLoaderVersion == opt.version,
                            onClick = { selectedLoaderVersion = opt.version },
                            label = { Text(opt.version) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NoxerNeonRed, selectedLabelColor = Color.White),
                        )
                    }
                }
            }
        }

        // 6 & 7: Memory Allocation & JVM Arguments
        NoxerPanel {
            Text("4. MEMORY ALLOCATION & JVM ARGUMENTS", style = MaterialTheme.typography.labelMedium, color = NoxerNeonRed)
            Spacer(Modifier.height(6.dp))
            Text("Heap Size (-Xmx): ${memoryMb.toInt()} MB", style = MaterialTheme.typography.bodyMedium, color = NoxerTextPrimary)
            Slider(
                value = memoryMb,
                onValueChange = { memoryMb = it },
                valueRange = 512f..6144f,
                steps = 21,
                colors = SliderDefaults.colors(thumbColor = NoxerNeonRed, activeTrackColor = NoxerNeonRed),
            )
            OutlinedTextField(
                value = jvmArgs,
                onValueChange = { jvmArgs = it },
                label = { Text("JVM Arguments") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NoxerNeonRed,
                    unfocusedBorderColor = NoxerBorder,
                    focusedTextColor = NoxerTextPrimary,
                    unfocusedTextColor = NoxerTextPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 8 & 9 & 10: Resolution, Graphics Renderer, Control Profile
        NoxerPanel {
            Text("5. GRAPHICS, RESOLUTION & CONTROLS", style = MaterialTheme.typography.labelMedium, color = NoxerNeonRed)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RendererBackend.entries.forEach { backend ->
                    FilterChip(
                        selected = selectedRenderer == backend.id,
                        onClick = { selectedRenderer = backend.id },
                        label = { Text(backend.name) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NoxerNeonRed, selectedLabelColor = Color.White),
                    )
                }
            }
            val chosenBackend = RendererBackend.entries.firstOrNull { it.id == selectedRenderer }
            chosenBackend?.shaderWarning?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = NoxerAmber)
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Resolution Scale: $scalePercent%", style = MaterialTheme.typography.bodyMedium, color = NoxerTextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(50, 75, 100).forEach { pct ->
                        FilterChip(
                            selected = scalePercent == pct,
                            onClick = { scalePercent = pct },
                            label = { Text("$pct%") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NoxerNeonRed, selectedLabelColor = Color.White),
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Fullscreen Viewport", style = MaterialTheme.typography.bodyMedium, color = NoxerTextPrimary)
                Switch(checked = fullscreen, onCheckedChange = { fullscreen = it })
            }
            Spacer(Modifier.height(6.dp))
            Text("Control Profile:", style = MaterialTheme.typography.labelSmall, color = NoxerTextMuted)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                controlProfiles.forEach { cp ->
                    FilterChip(
                        selected = selectedControlProfileId == cp.id,
                        onClick = { selectedControlProfileId = cp.id },
                        label = { Text(cp.name) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NoxerNeonRed, selectedLabelColor = Color.White),
                    )
                }
            }
        }

        Button(
            onClick = {
                if (editingExisting != null) {
                    onUpdateInstallation(
                        editingExisting.copy(
                            name = name.trim().ifBlank { editingExisting.name },
                            javaRuntimeId = selectedRuntimeId,
                            loaderType = selectedLoader.name,
                            loaderVersion = selectedLoaderVersion,
                            memoryMb = memoryMb.toInt(),
                            jvmArgs = jvmArgs,
                            resolutionWidth = resWidth,
                            resolutionHeight = resHeight,
                            resolutionScalePercent = scalePercent,
                            fullscreen = fullscreen,
                            rendererId = selectedRenderer,
                            controlProfileId = selectedControlProfileId,
                        )
                    )
                    onBack()
                } else {
                    scope.launch {
                        val exists = checkNameExists(name)
                        if (exists) {
                            showOverwriteDialog = true
                        } else {
                            onCreateInstallation(
                                CreateInstallationRequest(
                                    name = name,
                                    version = targetVersion,
                                    loaderType = selectedLoader,
                                    loaderVersion = selectedLoaderVersion,
                                    javaRuntimeId = selectedRuntimeId,
                                    customDirectoryName = directorySlug,
                                    memoryMb = memoryMb.toInt(),
                                    jvmArgs = jvmArgs,
                                    resolutionWidth = resWidth,
                                    resolutionHeight = resHeight,
                                    resolutionScalePercent = scalePercent,
                                    fullscreen = fullscreen,
                                    rendererId = selectedRenderer,
                                    controlProfileId = selectedControlProfileId,
                                    allowOverwriteExistingName = false,
                                )
                            )
                            onBack()
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("submit_create_installation_button"),
            colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
        ) {
            Text(
                text = if (editingExisting != null) "SAVE INSTALLATION CONFIG" else "CREATE INSTALLATION",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showOverwriteDialog) {
        AlertDialog(
            onDismissRequest = { showOverwriteDialog = false },
            containerColor = NoxerSurfaceElevated,
            title = { Text("Installation Name Exists", color = NoxerTextPrimary) },
            text = {
                Text(
                    "An installation named '$name' already exists. Choose a unique name to keep multiple installations of ${targetVersion.id}, or confirm overwrite.",
                    color = NoxerTextSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showOverwriteDialog = false
                        onCreateInstallation(
                            CreateInstallationRequest(
                                name = name,
                                version = targetVersion,
                                loaderType = selectedLoader,
                                loaderVersion = selectedLoaderVersion,
                                javaRuntimeId = selectedRuntimeId,
                                customDirectoryName = directorySlug,
                                memoryMb = memoryMb.toInt(),
                                jvmArgs = jvmArgs,
                                resolutionWidth = resWidth,
                                resolutionHeight = resHeight,
                                resolutionScalePercent = scalePercent,
                                fullscreen = fullscreen,
                                rendererId = selectedRenderer,
                                controlProfileId = selectedControlProfileId,
                                allowOverwriteExistingName = true,
                            )
                        )
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                ) {
                    Text("Overwrite")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showOverwriteDialog = false }, border = BorderStroke(1.dp, NoxerBorder)) {
                    Text("Rename")
                }
            },
        )
    }
}
