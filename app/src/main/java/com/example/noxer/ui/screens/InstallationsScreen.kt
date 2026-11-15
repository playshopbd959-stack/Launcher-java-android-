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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.noxer.data.db.InstallationEntity
import com.example.noxer.data.db.InstallationStatus
import com.example.noxer.download.DownloadProgressState
import com.example.noxer.ui.components.DownloadProgressBanner
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun InstallationsScreen(
    installations: List<InstallationEntity>,
    downloadProgress: DownloadProgressState,
    onPlay: (InstallationEntity) -> Unit,
    onEdit: (InstallationEntity) -> Unit,
    onDuplicate: (InstallationEntity) -> Unit,
    onRepair: (InstallationEntity) -> Unit,
    onDelete: (InstallationEntity) -> Unit,
    onOpenDirectory: (File) -> Unit,
    onOpenVersionCenter: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<InstallationEntity?>(null) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

    Column(modifier = Modifier.fillMaxSize().background(NoxerBlack)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("MY INSTALLATIONS", style = MaterialTheme.typography.headlineSmall, color = NoxerTextPrimary)
                Text("${installations.size} isolated game instance(s)", style = MaterialTheme.typography.labelSmall, color = NoxerTextSecondary)
            }
            Button(
                onClick = onOpenVersionCenter,
                colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
            ) {
                Text("+ New", style = MaterialTheme.typography.labelSmall)
            }
        }

        DownloadProgressBanner(state = downloadProgress, onCancel = onCancelDownload)

        if (installations.isEmpty()) {
            NoxerPanel(modifier = Modifier.padding(16.dp)) {
                Text("No installations yet", style = MaterialTheme.typography.titleMedium, color = NoxerTextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Each installation maintains its own .minecraft directory, mods, saves, shaders, and settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NoxerTextSecondary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(installations, key = { it.id }) { inst ->
                    val statusEnum = runCatching { InstallationStatus.valueOf(inst.status) }.getOrDefault(InstallationStatus.NOT_INSTALLED)
                    val statusColor = when (statusEnum) {
                        InstallationStatus.READY -> NoxerEmerald
                        InstallationStatus.DOWNLOADING, InstallationStatus.VERIFYING -> NoxerCyan
                        InstallationStatus.CORRUPTED, InstallationStatus.ERROR -> NoxerCrimsonGlow
                        InstallationStatus.NOT_INSTALLED -> NoxerAmber
                    }

                    NoxerPanel(modifier = Modifier.testTag("installation_card_${inst.id}")) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(inst.name, style = MaterialTheme.typography.titleLarge, color = NoxerTextPrimary)
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    NoxerBadge("MC ${inst.minecraftVersionId}", NoxerTextPrimary)
                                    NoxerBadge(inst.loaderType, NoxerCyan)
                                    NoxerBadge(statusEnum.label, statusColor)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Runtime: ${inst.javaRuntimeId} • RAM: ${inst.memoryMb} MB • Files: ${inst.verifiedFileCount}/${inst.totalFileCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NoxerTextSecondary,
                                )
                                Text(
                                    text = "Last played: ${inst.lastPlayedAt?.let { dateFormat.format(Date(it)) } ?: "Never"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NoxerTextMuted,
                                )
                                inst.lastError?.let { err ->
                                    Spacer(Modifier.height(4.dp))
                                    Text(err, style = MaterialTheme.typography.bodySmall, color = NoxerCrimsonGlow)
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { onPlay(inst) },
                                colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                                modifier = Modifier.testTag("inst_play_${inst.id}"),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("PLAY", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { onEdit(inst) },
                                border = BorderStroke(1.dp, NoxerBorder),
                                modifier = Modifier.testTag("inst_edit_${inst.id}"),
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp), tint = NoxerTextPrimary)
                                Spacer(Modifier.width(4.dp))
                                Text("EDIT", style = MaterialTheme.typography.labelSmall, color = NoxerTextPrimary)
                            }
                            OutlinedButton(
                                onClick = { onDuplicate(inst) },
                                border = BorderStroke(1.dp, NoxerBorder),
                                modifier = Modifier.testTag("inst_dup_${inst.id}"),
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp), tint = NoxerTextPrimary)
                                Spacer(Modifier.width(4.dp))
                                Text("DUPLICATE", style = MaterialTheme.typography.labelSmall, color = NoxerTextPrimary)
                            }
                            OutlinedButton(
                                onClick = { onRepair(inst) },
                                border = BorderStroke(1.dp, NoxerBorder),
                                modifier = Modifier.testTag("inst_repair_${inst.id}"),
                            ) {
                                Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(15.dp), tint = NoxerAmber)
                                Spacer(Modifier.width(4.dp))
                                Text("REPAIR", style = MaterialTheme.typography.labelSmall, color = NoxerAmber)
                            }
                            OutlinedButton(
                                onClick = { onOpenDirectory(File(inst.gameDirPath)) },
                                border = BorderStroke(1.dp, NoxerBorder),
                                modifier = Modifier.testTag("inst_dir_${inst.id}"),
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(15.dp), tint = NoxerCyan)
                                Spacer(Modifier.width(4.dp))
                                Text("OPEN DIR", style = MaterialTheme.typography.labelSmall, color = NoxerCyan)
                            }
                            OutlinedButton(
                                onClick = { confirmDelete = inst },
                                border = BorderStroke(1.dp, NoxerNeonRed.copy(alpha = 0.5f)),
                                modifier = Modifier.testTag("inst_delete_${inst.id}"),
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp), tint = NoxerCrimsonGlow)
                                Spacer(Modifier.width(4.dp))
                                Text("DELETE", style = MaterialTheme.typography.labelSmall, color = NoxerCrimsonGlow)
                            }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = NoxerSurfaceElevated,
            title = { Text("Delete '${target.name}'?", color = NoxerTextPrimary) },
            text = {
                Text(
                    "This permanently deletes the isolated game directory, worlds, mods, and configs for '${target.name}'. Shared version files remain cached.",
                    color = NoxerTextSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(target)
                        confirmDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                ) {
                    Text("Delete Instance", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDelete = null }, border = BorderStroke(1.dp, NoxerBorder)) {
                    Text("Cancel")
                }
            },
        )
    }
}
