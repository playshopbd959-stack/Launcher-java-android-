package com.example.noxer.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.noxer.data.db.RuntimeEntity
import com.example.noxer.graphics.GraphicsDiagnostics
import com.example.noxer.graphics.RendererBackend
import com.example.noxer.runtime.RuntimeDiagnostics
import com.example.noxer.runtime.RuntimeInstallProgress
import com.example.noxer.settings.LauncherSettings
import com.example.noxer.storage.StorageBreakdown
import com.example.noxer.storage.StorageManager
import com.example.noxer.ui.components.NoxerBadge
import com.example.noxer.ui.components.NoxerPanel
import com.example.ui.theme.NoxerAmber
import com.example.ui.theme.NoxerBlack
import com.example.ui.theme.NoxerBorder
import com.example.ui.theme.NoxerCrimsonGlow
import com.example.ui.theme.NoxerCyan
import com.example.ui.theme.NoxerEmerald
import com.example.ui.theme.NoxerNeonRed
import com.example.ui.theme.NoxerTextMuted
import com.example.ui.theme.NoxerTextPrimary
import com.example.ui.theme.NoxerTextSecondary
import java.io.File

@Composable
fun SettingsScreen(
    settings: LauncherSettings,
    runtimes: List<RuntimeEntity>,
    runtimeInstallProgress: RuntimeInstallProgress,
    hostDiagnostics: RuntimeDiagnostics,
    graphicsDiagnostics: GraphicsDiagnostics,
    storageBreakdown: StorageBreakdown?,
    rootInstancesDir: File,
    onUpdateSettings: ((LauncherSettings) -> LauncherSettings) -> Unit,
    onInstallRuntime: (Int) -> Unit,
    onVerifyRuntime: (RuntimeEntity) -> Unit,
    onDeleteRuntime: (RuntimeEntity) -> Unit,
    onOpenRuntimeManager: () -> Unit = {},
    onExportBackupSaf: (File, Uri) -> Unit,
    onRestoreBackupSaf: (Uri, File) -> Unit,
    onRefreshDiagnostics: () -> Unit,
) {
    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) onExportBackupSaf(rootInstancesDir, uri)
    }

    val restoreBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onRestoreBackupSaf(uri, rootInstancesDir)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(NoxerBlack),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("SETTINGS & RUNTIMES", style = MaterialTheme.typography.headlineSmall, color = NoxerTextPrimary)
            Text("Java Runtimes • Graphics • Memory • Storage & Backups", style = MaterialTheme.typography.bodySmall, color = NoxerTextSecondary)
        }

        // Java Runtime Engine
        item {
            NoxerPanel(highlighted = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "JAVA RUNTIME ENGINE (${hostDiagnostics.primaryAbi})",
                        style = MaterialTheme.typography.labelMedium,
                        color = NoxerNeonRed,
                    )
                    OutlinedButton(
                        onClick = onOpenRuntimeManager,
                        border = BorderStroke(1.dp, NoxerNeonRed.copy(alpha = 0.6f)),
                        modifier = Modifier.testTag("open_runtime_manager_button"),
                    ) {
                        Text("RUNTIME MANAGER", style = MaterialTheme.typography.labelSmall, color = NoxerNeonRed)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Supported ABIs: ${hostDiagnostics.hostAbis.joinToString()} • ARM64: ${if (hostDiagnostics.isArm64Host) "Compatible" else "No"} • Native JNI: ${if (hostDiagnostics.nativeBridgeLoaded) "Loaded" else "Standby"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NoxerTextSecondary,
                )
                Spacer(Modifier.height(10.dp))

                if (runtimeInstallProgress.isInstalling) {
                    Text(runtimeInstallProgress.statusText, style = MaterialTheme.typography.bodySmall, color = NoxerCyan)
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { runtimeInstallProgress.progressFraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = NoxerNeonRed,
                        trackColor = NoxerBlack,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Text("Quick Install Android-Bionic OpenJDK:", style = MaterialTheme.typography.labelSmall, color = NoxerTextMuted)
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(8, 17, 21, 25).forEach { major ->
                        Button(
                            onClick = { onInstallRuntime(major) },
                            enabled = !runtimeInstallProgress.isInstalling,
                            colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("install_java_$major"),
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Java $major", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                if (runtimes.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    runtimes.forEach { rt ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(rt.displayName, style = MaterialTheme.typography.titleSmall, color = NoxerTextPrimary)
                                    if (rt.isVerified) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = "Verified", tint = NoxerEmerald, modifier = Modifier.size(15.dp))
                                    }
                                    if (rt.isDefault) {
                                        NoxerBadge("DEFAULT", NoxerNeonRed)
                                    }
                                }
                                Text(rt.diagnosticMessage ?: rt.fullVersion, style = MaterialTheme.typography.labelSmall, color = NoxerTextSecondary)
                            }
                            OutlinedButton(onClick = { onVerifyRuntime(rt) }, border = BorderStroke(1.dp, NoxerBorder)) {
                                Text("Verify", style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { onDeleteRuntime(rt) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Runtime", tint = NoxerCrimsonGlow)
                            }
                        }
                    }
                }
            }
        }

        // Performance & Memory Settings
        item {
            NoxerPanel {
                Text("PERFORMANCE & DOWNLOADS", style = MaterialTheme.typography.labelMedium, color = NoxerNeonRed)
                Spacer(Modifier.height(8.dp))
                Text("Performance Profile:", style = MaterialTheme.typography.labelSmall, color = NoxerTextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Battery", "Balanced", "Performance").forEach { prof ->
                        FilterChip(
                            selected = settings.performanceProfile == prof,
                            onClick = { onUpdateSettings { it.copy(performanceProfile = prof) } },
                            label = { Text(prof) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NoxerNeonRed, selectedLabelColor = Color.White),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Default RAM Allocation: ${settings.defaultMemoryMb} MB", style = MaterialTheme.typography.bodyMedium, color = NoxerTextPrimary)
                Slider(
                    value = settings.defaultMemoryMb.toFloat(),
                    onValueChange = { v -> onUpdateSettings { it.copy(defaultMemoryMb = v.toInt()) } },
                    valueRange = 512f..6144f,
                    steps = 21,
                    colors = SliderDefaults.colors(thumbColor = NoxerNeonRed, activeTrackColor = NoxerNeonRed),
                )
                Text("Download Concurrency: ${settings.downloadConcurrency} threads", style = MaterialTheme.typography.bodyMedium, color = NoxerTextPrimary)
                Slider(
                    value = settings.downloadConcurrency.toFloat(),
                    onValueChange = { v -> onUpdateSettings { it.copy(downloadConcurrency = v.toInt()) } },
                    valueRange = 1f..12f,
                    steps = 10,
                    colors = SliderDefaults.colors(thumbColor = NoxerCyan, activeTrackColor = NoxerCyan),
                )
                OutlinedTextField(
                    value = settings.defaultJvmArgs,
                    onValueChange = { args -> onUpdateSettings { it.copy(defaultJvmArgs = args) } },
                    label = { Text("Default JVM Arguments") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NoxerNeonRed,
                        unfocusedBorderColor = NoxerBorder,
                        focusedTextColor = NoxerTextPrimary,
                        unfocusedTextColor = NoxerTextPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Verify SHA-1 Checksums on Launch", style = MaterialTheme.typography.bodyMedium, color = NoxerTextPrimary)
                    Switch(
                        checked = settings.verifySha1OnLaunch,
                        onCheckedChange = { checked -> onUpdateSettings { it.copy(verifySha1OnLaunch = checked) } },
                    )
                }
            }
        }

        // Graphics & EGL Hardware Diagnostics
        item {
            NoxerPanel {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("GRAPHICS & EGL HARDWARE", style = MaterialTheme.typography.labelMedium, color = NoxerNeonRed)
                    OutlinedButton(onClick = onRefreshDiagnostics, border = BorderStroke(1.dp, NoxerBorder)) {
                        Text("Re-Probe", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Vendor: ${graphicsDiagnostics.glVendor}", style = MaterialTheme.typography.bodySmall, color = NoxerTextPrimary)
                Text("Renderer: ${graphicsDiagnostics.glRenderer}", style = MaterialTheme.typography.bodySmall, color = NoxerTextSecondary)
                Text("GL Version: ${graphicsDiagnostics.glVersion}", style = MaterialTheme.typography.bodySmall, color = NoxerTextSecondary)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NoxerBadge("EGL ${graphicsDiagnostics.eglVersion}", NoxerCyan)
                    NoxerBadge(if (graphicsDiagnostics.gles3Supported) "GLES 3.0 OK" else "No GLES3", if (graphicsDiagnostics.gles3Supported) NoxerEmerald else NoxerAmber)
                    if (graphicsDiagnostics.astcSupported) NoxerBadge("ASTC", NoxerEmerald)
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RendererBackend.entries.forEach { b ->
                        FilterChip(
                            selected = settings.defaultRendererId == b.id,
                            onClick = { onUpdateSettings { it.copy(defaultRendererId = b.id) } },
                            label = { Text(b.name) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NoxerNeonRed, selectedLabelColor = Color.White),
                        )
                    }
                }
            }
        }

        // Storage & SAF Backup/Restore
        item {
            NoxerPanel {
                Text("STORAGE & SAF BACKUP", style = MaterialTheme.typography.labelMedium, color = NoxerNeonRed)
                Spacer(Modifier.height(8.dp))
                storageBreakdown?.let { sb ->
                    StorageRow("Versions", sb.versionsBytes)
                    StorageRow("Libraries", sb.librariesBytes)
                    StorageRow("Assets", sb.assetsBytes)
                    StorageRow("Java Runtimes", sb.runtimesBytes)
                    StorageRow("Game Instances", sb.instancesBytes)
                    StorageRow("Logs & Crashes", sb.logsAndCrashesBytes)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Total Used: ${StorageManager.formatBytes(sb.totalUsedBytes)} • Device Free: ${StorageManager.formatBytes(sb.availableDeviceBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NoxerEmerald,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { exportBackupLauncher.launch("noxer_instances_backup.zip") },
                        colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Backup ZIP", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = { restoreBackupLauncher.launch(arrayOf("application/zip")) },
                        border = BorderStroke(1.dp, NoxerBorder),
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp), tint = NoxerCyan)
                        Spacer(Modifier.width(4.dp))
                        Text("Restore ZIP", style = MaterialTheme.typography.labelSmall, color = NoxerCyan)
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageRow(label: String, bytes: Long) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = NoxerTextSecondary)
        Text(StorageManager.formatBytes(bytes), style = MaterialTheme.typography.labelSmall, color = NoxerTextPrimary)
    }
}
