package com.example.noxer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.noxer.data.db.ControlProfileEntity
import com.example.noxer.input.ConnectedInputDevices
import com.example.noxer.input.InputManager
import com.example.noxer.input.KeybindMapping
import com.example.noxer.input.TouchButtonConfig
import com.example.noxer.ui.components.NoxerBadge
import com.example.noxer.ui.components.NoxerPanel
import com.example.ui.theme.NoxerBlack
import com.example.ui.theme.NoxerBorder
import com.example.ui.theme.NoxerCyan
import com.example.ui.theme.NoxerEmerald
import com.example.ui.theme.NoxerNeonRed
import com.example.ui.theme.NoxerSurface
import com.example.ui.theme.NoxerSurfaceElevated
import com.example.ui.theme.NoxerTextMuted
import com.example.ui.theme.NoxerTextPrimary
import com.example.ui.theme.NoxerTextSecondary
import kotlin.math.roundToInt

@Composable
fun ControlsScreen(
    profiles: List<ControlProfileEntity>,
    connectedDevices: ConnectedInputDevices,
    decodeTouchButtons: (String) -> List<TouchButtonConfig>,
    decodeKeybinds: (String) -> List<KeybindMapping>,
    onSaveProfile: (ControlProfileEntity, List<TouchButtonConfig>, List<KeybindMapping>, Float, Boolean) -> Unit,
    onCreateProfile: (String) -> Unit,
    onRefreshDevices: () -> Unit,
) {
    var selectedProfileId by remember(profiles) { mutableStateOf(profiles.firstOrNull()?.id) }
    val activeProfile = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull()

    var buttons by remember(activeProfile?.id, activeProfile?.updatedAt) {
        mutableStateOf(activeProfile?.let { decodeTouchButtons(it.touchButtonsJson) } ?: InputManager.defaultTouchButtons())
    }
    var keybinds by remember(activeProfile?.id, activeProfile?.updatedAt) {
        mutableStateOf(activeProfile?.let { decodeKeybinds(it.keybindsJson) } ?: InputManager.defaultKeybinds())
    }
    var selectedButtonId by remember { mutableStateOf<String?>(buttons.firstOrNull()?.id) }
    var sensitivity by remember(activeProfile?.id) { mutableFloatStateOf(activeProfile?.mouseSensitivity ?: 1.0f) }
    var invertY by remember(activeProfile?.id) { mutableStateOf(activeProfile?.invertY ?: false) }

    var showNewProfileDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(NoxerBlack),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("CONTROLS & INPUT", style = MaterialTheme.typography.headlineSmall, color = NoxerTextPrimary)
                    Text("Touch Layout Editor • Keyboard • Mouse • Gamepad", style = MaterialTheme.typography.bodySmall, color = NoxerTextSecondary)
                }
                OutlinedButton(onClick = { showNewProfileDialog = true }, border = BorderStroke(1.dp, NoxerBorder)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Profile", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Profile Switcher
        item {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { profile ->
                    FilterChip(
                        selected = profile.id == activeProfile?.id,
                        onClick = { selectedProfileId = profile.id },
                        label = { Text(profile.name) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NoxerNeonRed, selectedLabelColor = Color.White),
                    )
                }
            }
        }

        // Interactive Touch Layout Editor Canvas
        item {
            NoxerPanel(highlighted = true) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("TOUCH LAYOUT EDITOR (DRAG TO MOVE)", style = MaterialTheme.typography.labelMedium, color = NoxerNeonRed)
                    activeProfile?.let { prof ->
                        Button(
                            onClick = { onSaveProfile(prof, buttons, keybinds, sensitivity, invertY) },
                            colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("save_controls_button"),
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(NoxerBlack, RoundedCornerShape(12.dp))
                        .border(1.dp, NoxerBorder, RoundedCornerShape(12.dp)),
                ) {
                    val density = LocalDensity.current
                    val wPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                    val hPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

                    buttons.forEach { btn ->
                        val sizePx = with(density) { (btn.sizeDp * 0.72f).dp.toPx() }
                        val xPx = (btn.xFraction * wPx - sizePx / 2f).coerceIn(0f, (wPx - sizePx).coerceAtLeast(0f))
                        val yPx = (btn.yFraction * hPx - sizePx / 2f).coerceIn(0f, (hPx - sizePx).coerceAtLeast(0f))
                        val isSel = btn.id == selectedButtonId

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
                                .size((btn.sizeDp * 0.72f).dp)
                                .alpha(btn.opacity)
                                .background(
                                    if (isSel) NoxerNeonRed.copy(alpha = 0.45f) else NoxerSurface,
                                    RoundedCornerShape(8.dp),
                                )
                                .border(1.5.dp, if (isSel) NoxerNeonRed else NoxerBorder, RoundedCornerShape(8.dp))
                                .clickable { selectedButtonId = btn.id }
                                .pointerInput(btn.id) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        selectedButtonId = btn.id
                                        val nx = ((xPx + drag.x + sizePx / 2f) / wPx).coerceIn(0.05f, 0.95f)
                                        val ny = ((yPx + drag.y + sizePx / 2f) / hPx).coerceIn(0.08f, 0.92f)
                                        buttons = buttons.map {
                                            if (it.id == btn.id) it.copy(xFraction = nx, yFraction = ny) else it
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(btn.label, style = MaterialTheme.typography.labelSmall, color = NoxerTextPrimary)
                        }
                    }
                }

                // Selected Button Size & Opacity Sliders
                val selBtn = buttons.firstOrNull { it.id == selectedButtonId }
                if (selBtn != null) {
                    Spacer(Modifier.height(10.dp))
                    Text("Editing Button: ${selBtn.label} (Size ${selBtn.sizeDp.toInt()}dp, Opacity ${(selBtn.opacity * 100).toInt()}%)", style = MaterialTheme.typography.labelSmall, color = NoxerTextPrimary)
                    Slider(
                        value = selBtn.sizeDp,
                        onValueChange = { newSize ->
                            buttons = buttons.map { if (it.id == selBtn.id) it.copy(sizeDp = newSize) else it }
                        },
                        valueRange = 40f..84f,
                        colors = SliderDefaults.colors(thumbColor = NoxerNeonRed, activeTrackColor = NoxerNeonRed),
                    )
                    Slider(
                        value = selBtn.opacity,
                        onValueChange = { newOp ->
                            buttons = buttons.map { if (it.id == selBtn.id) it.copy(opacity = newOp) else it }
                        },
                        valueRange = 0.25f..1.0f,
                        colors = SliderDefaults.colors(thumbColor = NoxerCyan, activeTrackColor = NoxerCyan),
                    )
                }
            }
        }

        // Mouse & Hardware Scanner
        item {
            NoxerPanel {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("MOUSE & HARDWARE INPUT", style = MaterialTheme.typography.labelMedium, color = NoxerNeonRed)
                    OutlinedButton(onClick = onRefreshDevices, border = BorderStroke(1.dp, NoxerBorder)) {
                        Text("Scan Devices", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NoxerBadge(
                        if (connectedDevices.hasPhysicalKeyboard) "Keyboard Connected" else "No Physical KB",
                        if (connectedDevices.hasPhysicalKeyboard) NoxerEmerald else NoxerTextMuted,
                    )
                    NoxerBadge(
                        if (connectedDevices.hasMouse) "Mouse Connected" else "No Mouse",
                        if (connectedDevices.hasMouse) NoxerEmerald else NoxerTextMuted,
                    )
                    NoxerBadge(
                        if (connectedDevices.gamepads.isNotEmpty()) "${connectedDevices.gamepads.size} Gamepad(s)" else "No Gamepad",
                        if (connectedDevices.gamepads.isNotEmpty()) NoxerCyan else NoxerTextMuted,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("Mouse Sensitivity: ${"%.2f".format(sensitivity)}x", style = MaterialTheme.typography.bodyMedium, color = NoxerTextPrimary)
                Slider(
                    value = sensitivity,
                    onValueChange = { sensitivity = it },
                    valueRange = 0.25f..3.0f,
                    colors = SliderDefaults.colors(thumbColor = NoxerNeonRed, activeTrackColor = NoxerNeonRed),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Invert Mouse Y-Axis", style = MaterialTheme.typography.bodyMedium, color = NoxerTextPrimary)
                    Switch(checked = invertY, onCheckedChange = { invertY = it })
                }
            }
        }

        // Physical Keyboard Mappings
        item {
            NoxerPanel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Keyboard, contentDescription = null, tint = NoxerNeonRed, modifier = Modifier.size(18.dp))
                    Text("KEYBOARD -> GLFW MAPPINGS", style = MaterialTheme.typography.labelMedium, color = NoxerNeonRed)
                }
                Spacer(Modifier.height(8.dp))
                keybinds.forEach { kb ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(kb.actionName, style = MaterialTheme.typography.bodySmall, color = NoxerTextPrimary)
                        NoxerBadge("${kb.displayKey} (GLFW ${kb.glfwKeyCode})", NoxerTextSecondary)
                    }
                }
            }
        }
    }

    if (showNewProfileDialog) {
        AlertDialog(
            onDismissRequest = { showNewProfileDialog = false },
            containerColor = NoxerSurfaceElevated,
            title = { Text("New Control Profile", color = NoxerTextPrimary) },
            text = {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it },
                    label = { Text("Profile Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onCreateProfile(newProfileName)
                        newProfileName = ""
                        showNewProfileDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showNewProfileDialog = false }) { Text("Cancel") }
            },
        )
    }
}
