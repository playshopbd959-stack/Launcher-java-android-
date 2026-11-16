package com.example.noxer.ui.screens

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.noxer.data.db.RuntimeEntity
import com.example.noxer.runtime.AndroidAbiInfo
import com.example.noxer.runtime.RuntimeDiagnostics
import com.example.noxer.runtime.RuntimeInstallProgress
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
import com.example.ui.theme.NoxerSurfaceElevated
import com.example.ui.theme.NoxerTextMuted
import com.example.ui.theme.NoxerTextPrimary
import com.example.ui.theme.NoxerTextSecondary

@Composable
fun JavaRuntimeManagerScreen(
    runtimes: List<RuntimeEntity>,
    installProgress: RuntimeInstallProgress,
    abiInfo: AndroidAbiInfo,
    hostDiagnostics: RuntimeDiagnostics,
    availableStorageBytes: Long,
    onInstallJava: (Int) -> Unit,
    onCancelInstall: () -> Unit,
    onUpdateRuntime: (RuntimeEntity) -> Unit,
    onVerifyRuntime: (RuntimeEntity) -> Unit,
    onSetDefaultRuntime: (RuntimeEntity) -> Unit,
    onRemoveRuntime: (RuntimeEntity) -> Unit,
    onBack: () -> Unit,
) {
    var selectedMajor by remember { mutableIntStateOf(21) }
    var customMajorInput by remember { mutableStateOf("") }
    var pendingRemove by remember { mutableStateOf<RuntimeEntity?>(null) }

    val supportedVersions = listOf(
        8 to "MC <= 1.16.5",
        17 to "MC 1.18 - 1.20.4",
        21 to "MC 1.20.5 - 1.21.11",
        25 to "MC 26+ / Next",
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(NoxerBlack)
            .testTag("runtime_manager_list"),
        contentPadding = PaddingValues(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("runtime_manager_back_button"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = NoxerTextPrimary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "JAVA RUNTIME MANAGER",
                        style = MaterialTheme.typography.headlineSmall,
                        color = NoxerTextPrimary,
                    )
                    Text(
                        text = "Android Bionic OpenJDK • Multi-Version Coexistence",
                        style = MaterialTheme.typography.bodySmall,
                        color = NoxerTextSecondary,
                    )
                }
            }
        }

        // Device ABI & ARM64 Compatibility Overview
        item {
            NoxerPanel(highlighted = true, modifier = Modifier.widthIn(max = 760.dp)) {
                Text(
                    text = "ANDROID DEVICE ARCHITECTURE",
                    style = MaterialTheme.typography.labelMedium,
                    color = NoxerNeonRed,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NoxerBadge("ABI: ${abiInfo.primaryAbi}", NoxerCyan)
                    NoxerBadge(
                        text = if (abiInfo.isArm64Compatible) "ARM64-v8a Compatible" else "Arch: ${abiInfo.elfMachineName}",
                        accentColor = if (abiInfo.isArm64Compatible) NoxerEmerald else NoxerAmber,
                    )
                    NoxerBadge(
                        text = if (hostDiagnostics.nativeBridgeLoaded) "JNI Ready" else "JNI Standby",
                        accentColor = if (hostDiagnostics.nativeBridgeLoaded) NoxerEmerald else NoxerAmber,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Supported ABIs: ${abiInfo.supportedAbis.joinToString()} • Free Storage: ${StorageManager.formatBytes(availableStorageBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NoxerTextSecondary,
                )
            }
        }

        // Install Java Panel
        item {
            NoxerPanel(modifier = Modifier.widthIn(max = 760.dp)) {
                Text(
                    text = "INSTALL ANDROID JAVA RUNTIME",
                    style = MaterialTheme.typography.labelMedium,
                    color = NoxerNeonRed,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Downloads genuine Android-Bionic OpenJDK builds into NOXER private storage and verifies real execution before registration.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NoxerTextSecondary,
                )
                Spacer(Modifier.height(10.dp))

                if (installProgress.isInstalling) {
                    Text(
                        text = "${installProgress.phase}: ${installProgress.statusText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NoxerCyan,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { installProgress.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = NoxerNeonRed,
                        trackColor = NoxerBlack,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onCancelInstall,
                        border = BorderStroke(1.dp, NoxerNeonRed),
                        modifier = Modifier.testTag("cancel_runtime_install_button"),
                    ) {
                        Text("CANCEL INSTALL", color = NoxerCrimsonGlow, style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    supportedVersions.forEach { (major, hint) ->
                        FilterChip(
                            selected = selectedMajor == major && customMajorInput.isBlank(),
                            onClick = {
                                selectedMajor = major
                                customMajorInput = ""
                            },
                            label = { Text("Java $major ($hint)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NoxerNeonRed,
                                selectedLabelColor = Color.White,
                            ),
                            modifier = Modifier.testTag("select_java_chip_$major"),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = customMajorInput,
                        onValueChange = { raw ->
                            customMajorInput = raw.filter { it.isDigit() }.take(2)
                        },
                        label = { Text("Other Java (e.g. 16, 22)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NoxerNeonRed,
                            unfocusedBorderColor = NoxerBorder,
                            focusedTextColor = NoxerTextPrimary,
                            unfocusedTextColor = NoxerTextPrimary,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("custom_java_version_input"),
                    )

                    val effectiveMajor = customMajorInput.toIntOrNull()?.takeIf { it in 8..30 } ?: selectedMajor
                    Button(
                        onClick = { onInstallJava(effectiveMajor) },
                        enabled = !installProgress.isInstalling,
                        colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        modifier = Modifier.testTag("install_java_primary_button"),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Install Java", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("INSTALL JAVA $effectiveMajor", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        // Installed Runtimes Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "INSTALLED RUNTIMES (${runtimes.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = NoxerTextSecondary,
                )
                Text(
                    text = "Coexists independently",
                    style = MaterialTheme.typography.labelSmall,
                    color = NoxerTextMuted,
                )
            }
        }

        if (runtimes.isEmpty()) {
            item {
                NoxerPanel {
                    Text(
                        text = "No Java runtimes installed yet.",
                        style = MaterialTheme.typography.titleMedium,
                        color = NoxerTextPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Press INSTALL JAVA above or press PLAY on any Minecraft installation to automatically install and verify the required Java runtime.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NoxerTextSecondary,
                    )
                }
            }
        } else {
            items(runtimes, key = { it.id }) { rt ->
                val statusLabel = when {
                    rt.isInstalled && rt.isVerified -> "VERIFIED"
                    rt.isInstalled -> "UNVERIFIED"
                    else -> "BROKEN"
                }
                val statusColor = when {
                    rt.isInstalled && rt.isVerified -> NoxerEmerald
                    rt.isInstalled -> NoxerAmber
                    else -> NoxerCrimsonGlow
                }

                NoxerPanel(
                    highlighted = rt.isDefault,
                    modifier = Modifier
                        .widthIn(max = 760.dp)
                        .testTag("runtime_card_${rt.id}"),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = if (rt.isVerified) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = statusLabel,
                                    tint = statusColor,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = rt.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = NoxerTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                NoxerBadge("Java ${rt.majorVersion}", NoxerCyan)
                                NoxerBadge(rt.abi, NoxerTextPrimary)
                                NoxerBadge(statusLabel, statusColor)
                                if (rt.isDefault) {
                                    NoxerBadge("DEFAULT", NoxerNeonRed)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    RuntimeInfoLine("Version Info", "${rt.fullVersion} (${rt.sourceProvider.ifBlank { "Android Bionic" }})")
                    RuntimeInfoLine("Architecture", rt.abi)
                    RuntimeInfoLine("Location", rt.installPath)
                    RuntimeInfoLine("Storage Size", StorageManager.formatBytes(rt.sizeBytes))
                    RuntimeInfoLine(
                        "Status",
                        rt.diagnosticMessage ?: if (rt.isVerified) "Verified & ready" else "Needs verification",
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onVerifyRuntime(rt) },
                            enabled = !installProgress.isInstalling,
                            colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                            modifier = Modifier.testTag("runtime_verify_${rt.id}"),
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("VERIFY", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = { onUpdateRuntime(rt) },
                            enabled = !installProgress.isInstalling,
                            border = BorderStroke(1.dp, NoxerBorder),
                            modifier = Modifier.testTag("runtime_update_${rt.id}"),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp), tint = NoxerCyan)
                            Spacer(Modifier.width(4.dp))
                            Text("UPDATE", style = MaterialTheme.typography.labelSmall, color = NoxerCyan)
                        }
                        OutlinedButton(
                            onClick = { onSetDefaultRuntime(rt) },
                            enabled = !rt.isDefault,
                            border = BorderStroke(1.dp, NoxerBorder),
                            modifier = Modifier.testTag("runtime_default_${rt.id}"),
                        ) {
                            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(15.dp), tint = NoxerAmber)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (rt.isDefault) "DEFAULT" else "SET DEFAULT",
                                style = MaterialTheme.typography.labelSmall,
                                color = NoxerAmber,
                            )
                        }
                        OutlinedButton(
                            onClick = { pendingRemove = rt },
                            enabled = !installProgress.isInstalling,
                            border = BorderStroke(1.dp, NoxerNeonRed.copy(alpha = 0.55f)),
                            modifier = Modifier.testTag("runtime_remove_${rt.id}"),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp), tint = NoxerCrimsonGlow)
                            Spacer(Modifier.width(4.dp))
                            Text("REMOVE", style = MaterialTheme.typography.labelSmall, color = NoxerCrimsonGlow)
                        }
                    }
                }
            }
        }
    }

    pendingRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            containerColor = NoxerSurfaceElevated,
            title = { Text("Remove ${target.displayName}?", color = NoxerTextPrimary) },
            text = {
                Text(
                    "Deletes the Java ${target.majorVersion} runtime files from NOXER private storage and clears installation assignments back to Auto.",
                    color = NoxerTextSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveRuntime(target)
                        pendingRemove = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                    modifier = Modifier.testTag("confirm_remove_runtime_button"),
                ) {
                    Text("REMOVE", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingRemove = null }, border = BorderStroke(1.dp, NoxerBorder)) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun RuntimeInfoLine(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = NoxerTextMuted)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = NoxerTextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
