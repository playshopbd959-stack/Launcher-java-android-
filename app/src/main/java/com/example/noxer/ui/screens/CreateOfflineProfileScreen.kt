package com.example.noxer.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.noxer.auth.OfflineProfileIcon
import com.example.noxer.auth.OfflineProfileValidator
import com.example.noxer.data.db.OfflineProfileEntity
import com.example.noxer.ui.components.NoxerBadge
import com.example.noxer.ui.components.NoxerPanel
import com.example.ui.theme.NoxerAmber
import com.example.ui.theme.NoxerBlack
import com.example.ui.theme.NoxerBorder
import com.example.ui.theme.NoxerCrimsonContainer
import com.example.ui.theme.NoxerCrimsonGlow
import com.example.ui.theme.NoxerEmerald
import com.example.ui.theme.NoxerNeonRed
import com.example.ui.theme.NoxerObsidian
import com.example.ui.theme.NoxerSurface
import com.example.ui.theme.NoxerSurfaceElevated
import com.example.ui.theme.NoxerTextMuted
import com.example.ui.theme.NoxerTextPrimary
import com.example.ui.theme.NoxerTextSecondary

fun offlineProfileIconVector(iconId: String?): ImageVector = when (OfflineProfileIcon.fromId(iconId)) {
    OfflineProfileIcon.SHIELD -> Icons.Default.Shield
    OfflineProfileIcon.BOLT -> Icons.Default.Bolt
    OfflineProfileIcon.COMPASS -> Icons.Default.Explore
    OfflineProfileIcon.TERMINAL -> Icons.Default.Terminal
    OfflineProfileIcon.HEXAGON -> Icons.Default.Hexagon
    OfflineProfileIcon.FLAME -> Icons.Default.LocalFireDepartment
    OfflineProfileIcon.STAR -> Icons.Default.Star
    OfflineProfileIcon.GAMEPAD -> Icons.Default.Gamepad
}

@Composable
fun OfflineProfileAvatar(
    iconId: String?,
    sizeDp: Int = 48,
    highlighted: Boolean = false,
) {
    val accent = if (highlighted) NoxerNeonRed else NoxerAmber
    Surface(
        modifier = Modifier.size(sizeDp.dp),
        color = accent.copy(alpha = 0.14f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = offlineProfileIconVector(iconId),
                contentDescription = "Offline Profile Icon",
                tint = accent,
                modifier = Modifier.size((sizeDp * 0.56f).dp),
            )
        }
    }
}

@Composable
fun CreateOfflineProfileScreen(
    existingProfiles: List<OfflineProfileEntity>,
    editingProfile: OfflineProfileEntity?,
    generatedPreviewUuid: String,
    onSaveNew: (username: String, displayName: String, iconId: String, notes: String, onDone: (Result<OfflineProfileEntity>) -> Unit) -> Unit,
    onSaveEdit: (profileId: String, username: String, displayName: String, iconId: String, notes: String, onDone: (Result<OfflineProfileEntity>) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val isEditing = editingProfile != null
    var username by remember(editingProfile?.id) { mutableStateOf(editingProfile?.username ?: "") }
    var displayName by remember(editingProfile?.id) { mutableStateOf(editingProfile?.displayName ?: "") }
    var selectedIconId by remember(editingProfile?.id) { mutableStateOf(editingProfile?.iconId ?: OfflineProfileIcon.SHIELD.id) }
    var notes by remember(editingProfile?.id) { mutableStateOf(editingProfile?.notes ?: "") }
    var submitError by remember { mutableStateOf<String?>(null) }

    val validation = remember(username, existingProfiles, editingProfile?.id) {
        OfflineProfileValidator.validateUsername(
            raw = username,
            existingProfiles = existingProfiles,
            editingProfileId = editingProfile?.id,
        )
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = NoxerNeonRed,
        unfocusedBorderColor = NoxerBorder,
        focusedTextColor = NoxerTextPrimary,
        unfocusedTextColor = NoxerTextPrimary,
        focusedLabelColor = NoxerNeonRed,
        unfocusedLabelColor = NoxerTextSecondary,
        cursorColor = NoxerNeonRed,
        focusedContainerColor = NoxerObsidian,
        unfocusedContainerColor = NoxerObsidian,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NoxerBlack),
    ) {
        Surface(
            color = NoxerObsidian,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("offline_profile_back_button"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = NoxerTextPrimary,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isEditing) "EDIT OFFLINE PROFILE" else "CREATE OFFLINE PROFILE",
                        style = MaterialTheme.typography.titleLarge,
                        color = NoxerTextPrimary,
                    )
                    Text(
                        text = "UNLICENSED / OFFLINE PROFILE CONFIGURATION",
                        style = MaterialTheme.typography.labelSmall,
                        color = NoxerAmber,
                    )
                }
                NoxerBadge("OFFLINE", NoxerAmber)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("create_offline_lazy_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Notice Banner explaining offline limitations clearly
            item {
                NoxerPanel(highlighted = false) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = NoxerAmber,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "UNLICENSED / OFFLINE PROFILE NOTICE",
                                style = MaterialTheme.typography.labelMedium,
                                color = NoxerAmber,
                            )
                            Text(
                                text = "Local offline profiles do not authenticate with Microsoft or Mojang services and do not represent a licensed Minecraft: Java Edition account. Use them for singleplayer offline gameplay and servers that explicitly permit offline-mode connections.",
                                style = MaterialTheme.typography.bodySmall,
                                color = NoxerTextSecondary,
                            )
                        }
                    }
                }
            }

            // Username & Display Name Form
            item {
                NoxerPanel(highlighted = true) {
                    Text(
                        text = "PLAYER IDENTITY",
                        style = MaterialTheme.typography.labelMedium,
                        color = NoxerNeonRed,
                    )
                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            username = it.take(16)
                            submitError = null
                        },
                        label = { Text("Player Username *") },
                        placeholder = { Text("e.g. NoxerBuilder_01") },
                        singleLine = true,
                        isError = username.isNotEmpty() && !validation.isValid,
                        colors = fieldColors,
                        supportingText = {
                            val helper = when {
                                username.isEmpty() -> "3-16 characters: letters (A-Z, a-z), numbers (0-9), underscore (_)."
                                validation.isValid -> "Valid Minecraft-compatible username (${validation.normalized.length}/16)."
                                else -> validation.error.orEmpty()
                            }
                            Text(
                                text = helper,
                                color = when {
                                    username.isEmpty() -> NoxerTextMuted
                                    validation.isValid -> NoxerEmerald
                                    else -> NoxerCrimsonGlow
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("offline_username_input"),
                    )

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it.take(32) },
                        label = { Text("Profile Display Name") },
                        placeholder = { Text("Optional friendly label (defaults to username)") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("offline_display_name_input"),
                    )

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it.take(240) },
                        label = { Text("Local Notes (Optional)") },
                        placeholder = { Text("e.g. Creative testing, local LAN world") },
                        minLines = 2,
                        maxLines = 3,
                        colors = fieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("offline_notes_input"),
                    )
                }
            }

            // Profile Icon Selection
            item {
                NoxerPanel {
                    Text(
                        text = "PROFILE ICON",
                        style = MaterialTheme.typography.labelMedium,
                        color = NoxerNeonRed,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(OfflineProfileIcon.entries) { iconOption ->
                            val isSelected = selectedIconId == iconOption.id
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { selectedIconId = iconOption.id }
                                    .testTag("offline_icon_${iconOption.id}"),
                                color = if (isSelected) NoxerCrimsonContainer else NoxerSurfaceElevated,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) NoxerNeonRed else NoxerBorder,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = offlineProfileIconVector(iconOption.id),
                                        contentDescription = iconOption.label,
                                        tint = if (isSelected) NoxerNeonRed else NoxerTextSecondary,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Text(
                                        text = iconOption.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) NoxerTextPrimary else NoxerTextMuted,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Local Profile Identifier Card
            item {
                val effectiveUuid = editingProfile?.id ?: generatedPreviewUuid
                NoxerPanel {
                    Text(
                        text = "LOCALLY GENERATED PROFILE IDENTIFIER",
                        style = MaterialTheme.typography.labelMedium,
                        color = NoxerTextSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = NoxerObsidian,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, NoxerBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = effectiveUuid,
                                style = MaterialTheme.typography.bodySmall,
                                color = NoxerTextPrimary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Stored strictly in Noxer's offline_profiles table. Isolated from Microsoft account UUIDs.",
                                style = MaterialTheme.typography.labelSmall,
                                color = NoxerTextMuted,
                            )
                        }
                    }
                }
            }

            if (submitError != null) {
                item {
                    Surface(
                        color = NoxerCrimsonContainer,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, NoxerNeonRed.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = submitError.orEmpty(),
                            color = NoxerCrimsonGlow,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            // Actions
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        border = BorderStroke(1.dp, NoxerBorder),
                        modifier = Modifier.weight(1f).height(50.dp),
                    ) {
                        Text("Cancel", color = NoxerTextSecondary)
                    }
                    Button(
                        onClick = {
                            if (!validation.isValid) {
                                submitError = validation.error ?: "Enter a valid Minecraft-compatible username."
                                return@Button
                            }
                            if (editingProfile != null) {
                                onSaveEdit(editingProfile.id, username, displayName, selectedIconId, notes) { res ->
                                    res.onSuccess { onBack() }
                                        .onFailure { submitError = it.message }
                                }
                            } else {
                                onSaveNew(username, displayName, selectedIconId, notes) { res ->
                                    res.onSuccess { onBack() }
                                        .onFailure { submitError = it.message }
                                }
                            }
                        },
                        enabled = validation.isValid,
                        colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                        modifier = Modifier
                            .weight(1.4f)
                            .height(50.dp)
                            .testTag("save_offline_profile_button"),
                    ) {
                        Text(
                            text = if (isEditing) "SAVE PROFILE" else "CREATE PROFILE",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}
