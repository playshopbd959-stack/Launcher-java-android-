package com.example.noxer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.noxer.download.DownloadProgressState
import com.example.noxer.launch.LaunchErrorCategory
import com.example.noxer.launch.LaunchPipelineState
import com.example.noxer.storage.DirectoryItemInfo
import com.example.noxer.storage.StorageManager
import com.example.ui.theme.NoxerAmber
import com.example.ui.theme.NoxerBlack
import com.example.ui.theme.NoxerBorder
import com.example.ui.theme.NoxerCrimsonContainer
import com.example.ui.theme.NoxerCrimsonGlow
import com.example.ui.theme.NoxerCyan
import com.example.ui.theme.NoxerNeonRed
import com.example.ui.theme.NoxerSurface
import com.example.ui.theme.NoxerSurfaceElevated
import com.example.ui.theme.NoxerTextMuted
import com.example.ui.theme.NoxerTextPrimary
import com.example.ui.theme.NoxerTextSecondary
import java.io.File

@Composable
fun NoxerPanel(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (highlighted) NoxerSurfaceElevated else NoxerSurface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (highlighted) NoxerNeonRed.copy(alpha = 0.45f) else NoxerBorder),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content,
        )
    }
}

@Composable
fun NoxerBadge(
    text: String,
    accentColor: Color = NoxerNeonRed,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = accentColor.copy(alpha = 0.14f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = accentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun DownloadProgressBanner(
    state: DownloadProgressState,
    onCancel: () -> Unit,
) {
    if (!state.isActive) return
    NoxerPanel(highlighted = true, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.phaseLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = NoxerTextPrimary,
                )
                Text(
                    text = "${state.completedFiles}/${state.totalFiles} files • ${StorageManager.formatBytes(state.downloadedBytes)} / ${StorageManager.formatBytes(state.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NoxerTextSecondary,
                )
                if (state.currentFileLabel.isNotBlank()) {
                    Text(
                        text = state.currentFileLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = NoxerTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            OutlinedButton(
                onClick = onCancel,
                border = BorderStroke(1.dp, NoxerNeonRed),
                modifier = Modifier.testTag("cancel_download_button"),
            ) {
                Text("Cancel", color = NoxerCrimsonGlow, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { state.fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = NoxerNeonRed,
            trackColor = NoxerBlack,
        )
    }
}

@Composable
fun LaunchStatusDialog(
    state: LaunchPipelineState,
    onDismiss: () -> Unit,
    onInstallJavaAndRetry: (majorVersion: Int, installationId: String?) -> Unit = { _, _ -> },
    onOpenRuntimeManager: () -> Unit = {},
) {
    if (!state.isLaunching && state.errorMessage == null) return
    val missingJava = state.missingJavaMajor
    AlertDialog(
        onDismissRequest = { if (!state.isLaunching) onDismiss() },
        containerColor = NoxerSurfaceElevated,
        title = {
            Text(
                text = state.errorCategory?.title ?: "Launching ${state.activeInstallationName ?: "Minecraft"}",
                color = if (state.errorMessage != null) NoxerCrimsonGlow else NoxerTextPrimary,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.authModeLabel.isNotBlank()) {
                    NoxerBadge(
                        text = state.authModeLabel,
                        accentColor = if (state.authModeLabel.contains("OFFLINE")) NoxerAmber else NoxerNeonRed,
                    )
                }
                if (state.isLaunching) {
                    Text(
                        text = "Step ${state.currentStep}/${state.totalSteps}: ${state.stepTitle}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NoxerTextPrimary,
                    )
                    LinearProgressIndicator(
                        progress = { (state.currentStep.toFloat() / state.totalSteps).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = NoxerNeonRed,
                        trackColor = NoxerBlack,
                    )
                    if (state.runtimeDownloadDetail.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = state.runtimeDownloadDetail,
                            style = MaterialTheme.typography.labelSmall,
                            color = NoxerCyan,
                        )
                        LinearProgressIndicator(
                            progress = { state.runtimeDownloadProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(5.dp),
                            color = NoxerCyan,
                            trackColor = NoxerBlack,
                        )
                    }
                }
                state.errorMessage?.let { err ->
                    Text(err, style = MaterialTheme.typography.bodyMedium, color = NoxerTextPrimary)
                }
                state.recoveryAction?.let { rec ->
                    Surface(
                        color = NoxerCrimsonContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = "Recovery: $rec",
                            style = MaterialTheme.typography.bodySmall,
                            color = NoxerAmber,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        },
        dismissButton = {
            if (!state.isLaunching && missingJava != null) {
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onOpenRuntimeManager()
                    },
                    border = BorderStroke(1.dp, NoxerBorder),
                    modifier = Modifier.testTag("dialog_open_runtime_manager_button"),
                ) {
                    Text("Runtime Manager", style = MaterialTheme.typography.labelSmall, color = NoxerCyan)
                }
            }
        },
        confirmButton = {
            if (!state.isLaunching) {
                if (missingJava != null && (state.errorCategory == LaunchErrorCategory.MISSING_JAVA_RUNTIME || state.errorCategory == LaunchErrorCategory.UNSUPPORTED_ARCHITECTURE)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onDismiss,
                            border = BorderStroke(1.dp, NoxerBorder),
                        ) {
                            Text("Close")
                        }
                        Button(
                            onClick = { onInstallJavaAndRetry(missingJava, state.activeInstallationId) },
                            colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                            modifier = Modifier.testTag("dialog_install_java_button"),
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("INSTALL JAVA $missingJava")
                        }
                    }
                } else {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                    ) {
                        Text("Dismiss")
                    }
                }
            }
        },
    )
}

@Composable
fun DirectoryBrowserDialog(
    currentDir: File,
    rootBoundary: File,
    items: List<DirectoryItemInfo>,
    onNavigate: (File) -> Unit,
    onBackupToSaf: (File) -> Unit,
    onReadPreview: (File) -> String,
    onDismiss: () -> Unit,
) {
    var previewTitle by remember { mutableStateOf<String?>(null) }
    var previewContent by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NoxerSurfaceElevated,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (currentDir.canonicalPath != rootBoundary.canonicalPath && currentDir.parentFile != null) {
                        IconButton(onClick = { onNavigate(currentDir.parentFile!!) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up Directory", tint = NoxerTextPrimary)
                        }
                    }
                    Column {
                        Text("Game Directory", style = MaterialTheme.typography.titleMedium, color = NoxerTextPrimary)
                        Text(
                            text = currentDir.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = NoxerTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close Directory Browser", tint = NoxerTextSecondary)
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (previewContent != null) {
                    Text(
                        text = previewTitle ?: "Preview",
                        style = MaterialTheme.typography.labelMedium,
                        color = NoxerNeonRed,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .background(NoxerBlack, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                    ) {
                        LazyColumn {
                            item {
                                Text(
                                    text = previewContent.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NoxerTextSecondary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { previewContent = null }) {
                        Text("Back to Files")
                    }
                } else if (items.isEmpty()) {
                    Text("Directory is empty.", style = MaterialTheme.typography.bodyMedium, color = NoxerTextMuted)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(items) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (item.isDirectory) {
                                            onNavigate(item.file)
                                        } else {
                                            previewTitle = item.name
                                            previewContent = onReadPreview(item.file)
                                        }
                                    }
                                    .padding(vertical = 8.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                    contentDescription = item.name,
                                    tint = if (item.isDirectory) NoxerNeonRed else NoxerTextSecondary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NoxerTextPrimary,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!item.isDirectory) {
                                    Text(
                                        text = StorageManager.formatBytes(item.sizeBytes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NoxerTextMuted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onBackupToSaf(currentDir) },
                colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
            ) {
                Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Export ZIP")
            }
        },
    )
}
