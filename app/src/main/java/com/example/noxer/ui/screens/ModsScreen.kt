package com.example.noxer.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.noxer.data.db.InstallationEntity
import com.example.noxer.data.db.LoaderType
import com.example.noxer.mods.InstalledModInfo
import com.example.noxer.mods.LoaderVersionOption
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

@Composable
fun ModsScreen(
    installations: List<InstallationEntity>,
    selectedInstanceId: String?,
    mods: List<InstalledModInfo>,
    compatibleLoaders: Map<LoaderType, List<LoaderVersionOption>>,
    onSelectInstance: (InstallationEntity) -> Unit,
    onInstallFabric: (InstallationEntity, String) -> Unit,
    onToggleMod: (InstallationEntity, InstalledModInfo) -> Unit,
    onDeleteMod: (InstallationEntity, InstalledModInfo) -> Unit,
    onImportModJar: (InstallationEntity, Uri, String) -> Unit,
    onImportMrpack: (InstallationEntity, Uri) -> Unit,
) {
    val context = LocalContext.current
    val selected = installations.firstOrNull { it.id == selectedInstanceId } ?: installations.firstOrNull()

    LaunchedEffect(selected?.id) {
        if (selected != null && selectedInstanceId != selected.id) {
            onSelectInstance(selected)
        }
    }

    val modJarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && selected != null) {
            var name = "mod.jar"
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
            onImportModJar(selected, uri, name)
        }
    }

    val mrpackPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && selected != null) {
            onImportMrpack(selected, uri)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(NoxerBlack),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("MODS & LOADERS", style = MaterialTheme.typography.headlineSmall, color = NoxerTextPrimary)
            Text("Independent per-instance mod profiles, Fabric/Forge/NeoForge & .mrpack", style = MaterialTheme.typography.bodySmall, color = NoxerTextSecondary)
        }

        if (installations.isEmpty()) {
            item {
                NoxerPanel {
                    Text("Create an installation first in Version Center to manage mods and loaders.", color = NoxerTextSecondary)
                }
            }
            return@LazyColumn
        }

        // Instance Selector
        item {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                installations.forEach { inst ->
                    FilterChip(
                        selected = inst.id == selected?.id,
                        onClick = { onSelectInstance(inst) },
                        label = { Text("${inst.name} (${inst.minecraftVersionId})") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NoxerNeonRed, selectedLabelColor = Color.White),
                    )
                }
            }
        }

        if (selected != null) {
            // Loader Status & Installer
            item {
                NoxerPanel(highlighted = true) {
                    Text("LOADER CONFIGURATION", style = MaterialTheme.typography.labelMedium, color = NoxerNeonRed)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NoxerBadge("MC ${selected.minecraftVersionId}", NoxerTextPrimary)
                        NoxerBadge(selected.loaderType, NoxerCyan)
                        selected.loaderVersion?.let { NoxerBadge(it, NoxerEmerald) }
                    }
                    Spacer(Modifier.height(10.dp))

                    val fabricOpts = compatibleLoaders[LoaderType.FABRIC].orEmpty()
                    if (fabricOpts.isNotEmpty()) {
                        Text("Compatible Fabric Versions (meta.fabricmc.net):", style = MaterialTheme.typography.labelSmall, color = NoxerTextSecondary)
                        Spacer(Modifier.height(4.dp))
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            fabricOpts.take(6).forEach { opt ->
                                Button(
                                    onClick = { onInstallFabric(selected, opt.version) },
                                    colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text("Install Fabric ${opt.version}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    val neoOpts = compatibleLoaders[LoaderType.NEOFORGE].orEmpty()
                    if (neoOpts.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "NeoForge available: ${neoOpts.take(3).joinToString { it.version }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NoxerCyan,
                        )
                    }

                    val forgeOpts = compatibleLoaders[LoaderType.FORGE].orEmpty()
                    if (forgeOpts.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Forge available: ${forgeOpts.joinToString { it.version }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NoxerAmber,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { modJarPicker.launch(arrayOf("application/java-archive", "*/*")) },
                            colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                            modifier = Modifier.testTag("add_mod_jar_button"),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add .JAR Mod", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = { mrpackPicker.launch(arrayOf("application/zip", "*/*")) },
                            border = BorderStroke(1.dp, NoxerBorder),
                            modifier = Modifier.testTag("import_mrpack_button"),
                        ) {
                            Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp), tint = NoxerCyan)
                            Spacer(Modifier.width(4.dp))
                            Text("Import .mrpack", style = MaterialTheme.typography.labelSmall, color = NoxerCyan)
                        }
                    }
                }
            }

            // Installed Mods List
            if (mods.isEmpty()) {
                item {
                    NoxerPanel {
                        Text("No .jar mods inside '${selected.name}' mods/ directory.", color = NoxerTextMuted)
                    }
                }
            } else {
                items(mods, key = { it.file.absolutePath }) { mod ->
                    NoxerPanel {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(mod.displayName, style = MaterialTheme.typography.titleMedium, color = NoxerTextPrimary)
                                Text(
                                    text = "${mod.modId} • v${mod.version} • ${StorageManager.formatBytes(mod.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NoxerTextSecondary,
                                )
                                mod.detectedLoader?.let {
                                    NoxerBadge(it.displayName, NoxerCyan)
                                }
                            }
                            Switch(
                                checked = mod.isEnabled,
                                onCheckedChange = { onToggleMod(selected, mod) },
                            )
                            IconButton(onClick = { onDeleteMod(selected, mod) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Mod", tint = NoxerCrimsonGlow)
                            }
                        }
                        mod.conflictWarning?.let { warn ->
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = NoxerAmber, modifier = Modifier.size(16.dp))
                                Text(warn, style = MaterialTheme.typography.bodySmall, color = NoxerAmber)
                            }
                        }
                    }
                }
            }
        }
    }
}
