package com.example.noxer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.noxer.ui.VersionDetailSheetState
import com.example.noxer.ui.components.NoxerBadge
import com.example.noxer.ui.components.NoxerPanel
import com.example.noxer.version.VersionCategory
import com.example.noxer.version.VersionCenterRepository
import com.example.noxer.version.VersionSortOrder
import com.example.noxer.version.VersionSummary
import com.example.ui.theme.NoxerAmber
import com.example.ui.theme.NoxerBlack
import com.example.ui.theme.NoxerBorder
import com.example.ui.theme.NoxerCrimsonGlow
import com.example.ui.theme.NoxerCyan
import com.example.ui.theme.NoxerEmerald
import com.example.ui.theme.NoxerNeonRed
import com.example.ui.theme.NoxerSurface
import com.example.ui.theme.NoxerSurfaceElevated
import com.example.ui.theme.NoxerTextMuted
import com.example.ui.theme.NoxerTextPrimary
import com.example.ui.theme.NoxerTextSecondary

@Composable
fun VersionCenterScreen(
    versions: List<VersionSummary>,
    installedVersionIds: Set<String>,
    searchQuery: String,
    selectedCategory: VersionCategory,
    sortOrder: VersionSortOrder,
    isSyncing: Boolean,
    syncError: String?,
    detailSheet: VersionDetailSheetState?,
    onSearchChange: (String) -> Unit,
    onCategorySelect: (VersionCategory) -> Unit,
    onToggleSort: () -> Unit,
    onSyncManifest: () -> Unit,
    onSelectVersionDetails: (VersionSummary) -> Unit,
    onDismissVersionDetails: () -> Unit,
    onCreateInstallation: (VersionSummary) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(NoxerBlack)) {
        // Header + Refresh
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("VERSION CENTER", style = MaterialTheme.typography.headlineSmall, color = NoxerTextPrimary)
                Text(
                    text = "${versions.size} official Mojang builds matching filter",
                    style = MaterialTheme.typography.labelSmall,
                    color = NoxerTextSecondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = onToggleSort,
                    modifier = Modifier.testTag("version_sort_button"),
                ) {
                    Icon(Icons.Default.SwapVert, contentDescription = sortOrder.label, tint = NoxerTextPrimary)
                }
                IconButton(
                    onClick = onSyncManifest,
                    enabled = !isSyncing,
                    modifier = Modifier.testTag("version_sync_button"),
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = NoxerNeonRed, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync Mojang Manifest", tint = NoxerNeonRed)
                    }
                }
            }
        }

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Search version (e.g. 1.21.4, 1.20.1, 1.12.2, 1.8.9, b1.7.3)", color = NoxerTextMuted) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = NoxerNeonRed) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search", tint = NoxerTextSecondary)
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NoxerNeonRed,
                unfocusedBorderColor = NoxerBorder,
                focusedTextColor = NoxerTextPrimary,
                unfocusedTextColor = NoxerTextPrimary,
                focusedContainerColor = NoxerSurface,
                unfocusedContainerColor = NoxerSurface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("version_search_input"),
        )

        Spacer(Modifier.height(8.dp))

        // Category Filter Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VersionCategory.entries.forEach { cat ->
                val isSelected = cat == selectedCategory
                FilterChip(
                    selected = isSelected,
                    onClick = { onCategorySelect(cat) },
                    label = { Text(cat.label, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NoxerNeonRed,
                        selectedLabelColor = Color.White,
                        containerColor = NoxerSurface,
                        labelColor = NoxerTextSecondary,
                    ),
                    modifier = Modifier.testTag("version_cat_${cat.name.lowercase()}"),
                )
            }
        }

        if (syncError != null) {
            Text(
                text = syncError,
                style = MaterialTheme.typography.bodySmall,
                color = NoxerAmber,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(6.dp))

        if (versions.isEmpty()) {
            NoxerPanel(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isSyncing) "Fetching official Mojang version_manifest_v2.json..." else "No versions match your current filter.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NoxerTextSecondary,
                )
                if (!isSyncing) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onSyncManifest,
                        colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                    ) {
                        Text("Sync Official Manifest")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(versions, key = { it.id }) { version ->
                    val isInstalled = installedVersionIds.contains(version.id)
                    val requiredJava = VersionCenterRepository.inferRequiredJavaMajor(version.id, null)
                    val typeColor = when (version.type) {
                        "release" -> NoxerEmerald
                        "snapshot" -> NoxerCyan
                        else -> NoxerAmber
                    }

                    NoxerPanel(
                        modifier = Modifier
                            .clickable { onSelectVersionDetails(version) }
                            .testTag("version_card_${version.id}"),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = version.id,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = NoxerTextPrimary,
                                    )
                                    NoxerBadge(version.type.uppercase(), typeColor)
                                    if (isInstalled) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Installed",
                                            tint = NoxerEmerald,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Released: ${version.releaseTime.take(10)} • Java $requiredJava • Official Manifest",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NoxerTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            Button(
                                onClick = { onCreateInstallation(version) },
                                colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("create_install_${version.id}"),
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("CREATE", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    // Version Details Modal
    detailSheet?.let { sheet ->
        AlertDialog(
            onDismissRequest = onDismissVersionDetails,
            containerColor = NoxerSurfaceElevated,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Minecraft ${sheet.summary.id}", style = MaterialTheme.typography.headlineSmall, color = NoxerTextPrimary)
                        Text(sheet.summary.type.uppercase(), style = MaterialTheme.typography.labelSmall, color = NoxerNeonRed)
                    }
                    IconButton(onClick = onDismissVersionDetails) {
                        Icon(Icons.Default.Close, contentDescription = "Close Details", tint = NoxerTextSecondary)
                    }
                }
            },
            text = {
                if (sheet.isLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = NoxerNeonRed)
                        Text("Fetching official Mojang & loader metadata...", color = NoxerTextSecondary)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val details = sheet.details
                        DetailRow("Release Date", sheet.summary.releaseTime.take(19).replace("T", " "))
                        DetailRow("Manifest SHA-1", sheet.summary.sha1.take(16) + "...")
                        DetailRow(
                            "Required Java",
                            "Java ${VersionCenterRepository.inferRequiredJavaMajor(sheet.summary.id, details)}" +
                                (details?.javaVersion?.component?.let { " ($it)" } ?: ""),
                        )
                        DetailRow(
                            "Installation Status",
                            if (installedVersionIds.contains(sheet.summary.id)) "Installed" else "Not Installed",
                        )
                        if (details != null) {
                            DetailRow("Main Class", details.mainClass)
                            DetailRow("Asset Index", details.assetIndex?.id ?: "legacy")
                            DetailRow("Libraries", "${details.libraries.size} entries")
                            DetailRow(
                                "Client Size",
                                "${((details.downloads?.client?.size ?: 0L) / (1024.0 * 1024.0)).let { "%.1f MB".format(it) }}",
                            )
                        }
                        sheet.error?.let {
                            Text("Metadata error: $it", color = NoxerCrimsonGlow, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("COMPATIBLE MOD LOADERS", style = MaterialTheme.typography.labelSmall, color = NoxerTextMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            sheet.compatibleLoaders.keys.forEach { loader ->
                                NoxerBadge(loader.displayName, NoxerCyan)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDismissVersionDetails()
                        onCreateInstallation(sheet.summary)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                    modifier = Modifier.testTag("details_create_installation_btn"),
                ) {
                    Text("CREATE INSTALLATION")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = onDismissVersionDetails, border = BorderStroke(1.dp, NoxerBorder)) {
                    Text("Close", color = NoxerTextSecondary)
                }
            },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = NoxerTextMuted)
        Text(value, style = MaterialTheme.typography.labelSmall, color = NoxerTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
