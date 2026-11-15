package com.example.noxer.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.noxer.auth.ActiveIdentity
import com.example.noxer.data.db.InstallationEntity
import com.example.noxer.data.db.InstallationStatus
import com.example.noxer.data.db.RuntimeEntity
import com.example.noxer.download.DownloadProgressState
import com.example.noxer.graphics.LivePerformanceMetrics
import com.example.noxer.nativebridge.NoxerNativeBridge
import com.example.noxer.ui.components.DownloadProgressBanner
import com.example.noxer.ui.components.NoxerBadge
import com.example.noxer.ui.components.NoxerPanel
import com.example.noxer.version.LatestVersions
import com.example.ui.theme.NoxerAmber
import com.example.ui.theme.NoxerBlack
import com.example.ui.theme.NoxerCyan
import com.example.ui.theme.NoxerEmerald
import com.example.ui.theme.NoxerNeonRed
import com.example.ui.theme.NoxerSurfaceElevated
import com.example.ui.theme.NoxerTextMuted
import com.example.ui.theme.NoxerTextPrimary
import com.example.ui.theme.NoxerTextSecondary

@Composable
fun HomeScreen(
    activeIdentity: ActiveIdentity,
    installations: List<InstallationEntity>,
    selectedInstallationId: String?,
    runtimes: List<RuntimeEntity>,
    latestVersions: LatestVersions,
    downloadProgress: DownloadProgressState,
    metrics: LivePerformanceMetrics,
    onPlaySelected: (InstallationEntity) -> Unit,
    onSelectInstallation: (String) -> Unit,
    onOpenVersionCenter: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenRuntimeManager: () -> Unit = {},
    onCancelDownload: () -> Unit,
) {
    val selected = installations.firstOrNull { it.id == selectedInstallationId } ?: installations.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(NoxerBlack),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Hero Banner with NOXER Branding & User Profile Pill
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_noxer_hero),
                    contentDescription = "Noxer Launcher Hero",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    NoxerBlack.copy(alpha = 0.25f),
                                    NoxerBlack.copy(alpha = 0.72f),
                                    NoxerBlack,
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "NOXER",
                                style = MaterialTheme.typography.displaySmall,
                                color = NoxerTextPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "JAVA EDITION ENGINE • ${NoxerNativeBridge.getArchitecture().uppercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = NoxerNeonRed,
                            )
                        }

                        // Account Pill
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .clickable(onClick = onOpenAccounts)
                                .testTag("home_account_pill"),
                            color = NoxerSurfaceElevated.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(24.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                when (activeIdentity) {
                                    is ActiveIdentity.Microsoft -> {
                                        AsyncImage(
                                            model = "https://mc-heads.net/avatar/${activeIdentity.account.uuid.replace("-", "")}/64",
                                            contentDescription = activeIdentity.account.username,
                                            modifier = Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)),
                                        )
                                        Text(
                                            text = activeIdentity.account.username,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = NoxerTextPrimary,
                                        )
                                        if (activeIdentity.account.ownsMinecraft) {
                                            NoxerBadge("VERIFIED", NoxerEmerald)
                                        }
                                    }
                                    is ActiveIdentity.Offline -> {
                                        OfflineProfileAvatar(iconId = activeIdentity.profile.iconId, sizeDp = 24, highlighted = false)
                                        Text(
                                            text = activeIdentity.profile.username,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = NoxerTextPrimary,
                                        )
                                        NoxerBadge("OFFLINE", NoxerAmber)
                                    }
                                    is ActiveIdentity.None -> {
                                        Icon(
                                            Icons.Default.AccountCircle,
                                            contentDescription = "Accounts",
                                            tint = NoxerNeonRed,
                                            modifier = Modifier.size(22.dp),
                                        )
                                        Text(
                                            text = "Accounts",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = NoxerTextPrimary,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (latestVersions.release.isNotBlank()) {
                            NoxerBadge("Release ${latestVersions.release}", NoxerEmerald)
                        }
                        if (latestVersions.snapshot.isNotBlank()) {
                            NoxerBadge("Snapshot ${latestVersions.snapshot}", NoxerCyan)
                        }
                        NoxerBadge(
                            text = "${runtimes.count { it.isInstalled && it.isVerified }} JREs",
                            accentColor = NoxerAmber,
                            modifier = Modifier
                                .clickable(onClick = onOpenRuntimeManager)
                                .testTag("home_jre_badge"),
                        )
                    }
                }
            }
        }

        // Real Download Status
        item {
            DownloadProgressBanner(state = downloadProgress, onCancel = onCancelDownload)
        }

        // Selected Installation + PLAY Command Panel
        item {
            NoxerPanel(
                highlighted = true,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                if (selected != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SELECTED INSTALLATION",
                                style = MaterialTheme.typography.labelSmall,
                                color = NoxerNeonRed,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = selected.name,
                                style = MaterialTheme.typography.headlineSmall,
                                color = NoxerTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                NoxerBadge("MC ${selected.minecraftVersionId}", NoxerTextPrimary)
                                NoxerBadge(selected.loaderType, NoxerCyan)
                                NoxerBadge("${selected.memoryMb} MB", NoxerAmber)
                            }
                        }
                        Icon(
                            imageVector = if (selected.status == InstallationStatus.READY.name) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = selected.status,
                            tint = if (selected.status == InstallationStatus.READY.name) NoxerEmerald else NoxerAmber,
                            modifier = Modifier.size(28.dp),
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { onPlaySelected(selected) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("home_play_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when (activeIdentity) {
                                is ActiveIdentity.Offline -> "LAUNCH OFFLINE (${activeIdentity.profile.username})"
                                is ActiveIdentity.Microsoft -> "LAUNCH MINECRAFT JAVA"
                                is ActiveIdentity.None -> "SELECT ACCOUNT TO LAUNCH"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                        )
                    }
                } else {
                    Text(
                        text = "NO INSTALLATION CONFIGURED",
                        style = MaterialTheme.typography.titleMedium,
                        color = NoxerTextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Browse the official Mojang Version Center to download Minecraft Java Edition and create your first isolated game instance.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NoxerTextSecondary,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = onOpenVersionCenter,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("home_browse_versions_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("OPEN VERSION CENTER", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        // System Telemetry Strip (Real Runtime Memory & Native Diagnostics)
        item {
            NoxerPanel(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Memory, contentDescription = null, tint = NoxerNeonRed, modifier = Modifier.size(20.dp))
                        Text("SYSTEM TELEMETRY", style = MaterialTheme.typography.labelMedium, color = NoxerTextPrimary)
                    }
                    Text(
                        text = if (NoxerNativeBridge.isNativeLibraryLoaded()) "JNI NATIVE ONLINE" else "JVM TEST MODE",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (NoxerNativeBridge.isNativeLibraryLoaded()) NoxerEmerald else NoxerAmber,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricCell("Device Free RAM", "${metrics.deviceFreeRamMb} MB")
                    MetricCell("Total RAM", "${metrics.deviceTotalRamMb} MB")
                    MetricCell("Launcher Heap", "${metrics.javaHeapUsedMb}/${metrics.javaHeapMaxMb} MB")
                }
            }
        }

        // Recent Installations
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("RECENT INSTALLATIONS", style = MaterialTheme.typography.labelMedium, color = NoxerTextSecondary)
                OutlinedButton(
                    onClick = onOpenVersionCenter,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New Instance", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (installations.isEmpty()) {
            item {
                NoxerPanel(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = "No instances created yet. Tap 'New Instance' or open Version Center to pick any release, snapshot, or historical version.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NoxerTextMuted,
                    )
                }
            }
        } else {
            items(installations.take(5), key = { it.id }) { inst ->
                val isSelected = inst.id == selected?.id
                NoxerPanel(
                    highlighted = isSelected,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clickable { onSelectInstallation(inst.id) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(inst.name, style = MaterialTheme.typography.titleMedium, color = NoxerTextPrimary)
                            Text(
                                text = "MC ${inst.minecraftVersionId} • ${inst.loaderType} • ${inst.rendererId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = NoxerTextSecondary,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (inst.status == InstallationStatus.READY.name) NoxerEmerald else NoxerAmber)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = NoxerTextMuted)
        Text(value, style = MaterialTheme.typography.titleSmall, color = NoxerTextPrimary)
    }
}
