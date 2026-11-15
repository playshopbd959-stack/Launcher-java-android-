package com.example.noxer.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.noxer.data.db.ConsoleLogEntity
import com.example.noxer.data.db.CrashReportEntity
import com.example.noxer.ui.components.NoxerBadge
import com.example.noxer.ui.components.NoxerPanel
import com.example.ui.theme.NoxerAmber
import com.example.ui.theme.NoxerBlack
import com.example.ui.theme.NoxerBorder
import com.example.ui.theme.NoxerCrimsonGlow
import com.example.ui.theme.NoxerCyan
import com.example.ui.theme.NoxerEmerald
import com.example.ui.theme.NoxerNeonRed
import com.example.ui.theme.NoxerSurface
import com.example.ui.theme.NoxerTextMuted
import com.example.ui.theme.NoxerTextPrimary
import com.example.ui.theme.NoxerTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConsoleScreen(
    logs: List<ConsoleLogEntity>,
    crashReports: List<CrashReportEntity>,
    onExportLogs: (Uri) -> Unit,
    onClearLogs: () -> Unit,
    onDeleteCrashReport: (String) -> Unit,
) {
    var showCrashesTab by remember { mutableStateOf(false) }
    var levelFilter by remember { mutableStateOf("ALL") }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    val exportLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) onExportLogs(uri)
    }

    Column(modifier = Modifier.fillMaxSize().background(NoxerBlack)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("CONSOLE & CRASHES", style = MaterialTheme.typography.headlineSmall, color = NoxerTextPrimary)
                Text("${logs.size} log lines • ${crashReports.size} crash report(s)", style = MaterialTheme.typography.labelSmall, color = NoxerTextSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { exportLogLauncher.launch("noxer-latest.log") },
                    colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("export_logs_button"),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Export", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = onClearLogs,
                    border = BorderStroke(1.dp, NoxerBorder),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("Clear", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Toggle Live Logs vs Crash Reports
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !showCrashesTab,
                onClick = { showCrashesTab = false },
                label = { Text("Live Logs") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NoxerNeonRed, selectedLabelColor = Color.White),
            )
            FilterChip(
                selected = showCrashesTab,
                onClick = { showCrashesTab = true },
                label = { Text("Crash Reports (${crashReports.size})") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NoxerNeonRed, selectedLabelColor = Color.White),
            )
        }

        if (!showCrashesTab) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("ALL", "INFO", "WARN", "ERROR").forEach { lvl ->
                    FilterChip(
                        selected = levelFilter == lvl,
                        onClick = { levelFilter = lvl },
                        label = { Text(lvl, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NoxerSurface,
                            selectedLabelColor = NoxerNeonRed,
                        ),
                    )
                }
            }

            val visibleLogs = if (levelFilter == "ALL") logs else logs.filter { it.level == levelFilter }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(visibleLogs, key = { it.id }) { entry ->
                    val color = when (entry.level) {
                        "ERROR" -> NoxerCrimsonGlow
                        "WARN" -> NoxerAmber
                        "DEBUG" -> NoxerCyan
                        else -> NoxerTextSecondary
                    }
                    Text(
                        text = "[${timeFmt.format(Date(entry.timestamp))}] [${entry.level}/${entry.source}] ${entry.message}",
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                    )
                }
            }
        } else {
            if (crashReports.isEmpty()) {
                NoxerPanel(modifier = Modifier.padding(16.dp)) {
                    Text("No crash reports recorded.", color = NoxerEmerald)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(crashReports, key = { it.id }) { report ->
                        NoxerPanel(highlighted = true) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.BugReport, contentDescription = null, tint = NoxerCrimsonGlow, modifier = Modifier.size(18.dp))
                                    Text(report.category, style = MaterialTheme.typography.titleMedium, color = NoxerCrimsonGlow)
                                }
                                IconButton(onClick = { onDeleteCrashReport(report.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete Report", tint = NoxerTextMuted)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                NoxerBadge(report.installationName, NoxerTextPrimary)
                                NoxerBadge("MC ${report.minecraftVersion}", NoxerCyan)
                                NoxerBadge("Exit ${report.exitCode}", NoxerAmber)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(report.summary, style = MaterialTheme.typography.bodyMedium, color = NoxerTextPrimary)
                            Spacer(Modifier.height(4.dp))
                            Text("Recovery: ${report.recoveryInstructions}", style = MaterialTheme.typography.bodySmall, color = NoxerAmber)
                            if (report.rawExcerpt.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = report.rawExcerpt.takeLast(500),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NoxerTextMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
