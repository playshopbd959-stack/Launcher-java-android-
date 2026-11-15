package com.example.noxer.input

import android.content.Context
import android.hardware.input.InputManager as AndroidInputManager
import android.view.InputDevice
import android.view.KeyEvent
import com.example.noxer.data.db.ControlProfileDao
import com.example.noxer.data.db.ControlProfileEntity
import com.example.noxer.nativebridge.NoxerNativeBridge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class TouchButtonConfig(
    val id: String,
    val label: String,
    val xFraction: Float, // 0f..1f across viewport width
    val yFraction: Float, // 0f..1f across viewport height
    val sizeDp: Float = 54f,
    val opacity: Float = 0.72f,
    val glfwCode: Int,
    val isMouseButton: Boolean = false,
)

@Serializable
data class KeybindMapping(
    val actionName: String,
    val androidKeyCode: Int,
    val glfwKeyCode: Int,
    val displayKey: String,
)

data class ConnectedInputDevices(
    val hasPhysicalKeyboard: Boolean = false,
    val hasMouse: Boolean = false,
    val gamepads: List<String> = emptyList(),
)

class InputManager(
    private val context: Context,
    private val dao: ControlProfileDao,
    private val json: Json,
) {
    val profiles: Flow<List<ControlProfileEntity>> = dao.observeAll()

    private val _connectedDevices = MutableStateFlow(scanConnectedDevices())
    val connectedDevices: StateFlow<ConnectedInputDevices> = _connectedDevices.asStateFlow()

    suspend fun ensureDefaultProfile() {
        if (dao.getById("default_touch") == null) {
            dao.upsert(
                ControlProfileEntity(
                    id = "default_touch",
                    name = "Noxer Combat & Build",
                    isDefault = true,
                    touchButtonsJson = json.encodeToString(defaultTouchButtons()),
                    keybindsJson = json.encodeToString(defaultKeybinds()),
                    mouseSensitivity = 1.0f,
                    invertY = false,
                )
            )
        }
    }

    fun decodeTouchButtons(rawJson: String): List<TouchButtonConfig> =
        runCatching { json.decodeFromString<List<TouchButtonConfig>>(rawJson) }
            .getOrElse { defaultTouchButtons() }

    fun decodeKeybinds(rawJson: String): List<KeybindMapping> =
        runCatching { json.decodeFromString<List<KeybindMapping>>(rawJson) }
            .getOrElse { defaultKeybinds() }

    suspend fun saveProfile(
        existing: ControlProfileEntity,
        buttons: List<TouchButtonConfig>,
        keybinds: List<KeybindMapping>,
        sensitivity: Float,
        invertY: Boolean,
    ) {
        dao.upsert(
            existing.copy(
                touchButtonsJson = json.encodeToString(buttons),
                keybindsJson = json.encodeToString(keybinds),
                mouseSensitivity = sensitivity,
                invertY = invertY,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun createNewProfile(name: String): ControlProfileEntity {
        val profile = ControlProfileEntity(
            id = "profile_${UUID.randomUUID().toString().take(8)}",
            name = name.trim().ifBlank { "Custom Profile" },
            isDefault = false,
            touchButtonsJson = json.encodeToString(defaultTouchButtons()),
            keybindsJson = json.encodeToString(defaultKeybinds()),
            mouseSensitivity = 1.0f,
            invertY = false,
        )
        dao.upsert(profile)
        return profile
    }

    suspend fun deleteProfile(profile: ControlProfileEntity) {
        if (!profile.isDefault) {
            dao.deleteById(profile.id)
        }
    }

    fun refreshConnectedDevices() {
        _connectedDevices.value = scanConnectedDevices()
    }

    private fun scanConnectedDevices(): ConnectedInputDevices {
        val sysInput = context.getSystemService(Context.INPUT_SERVICE) as? AndroidInputManager
        var keyboard = false
        var mouse = false
        val pads = mutableListOf<String>()

        sysInput?.inputDeviceIds?.forEach { id ->
            val dev = sysInput.getInputDevice(id) ?: return@forEach
            if (!dev.isVirtual) {
                val sources = dev.sources
                if ((sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD &&
                    dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
                ) {
                    keyboard = true
                }
                if ((sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
                    mouse = true
                }
                if ((sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                ) {
                    pads += dev.name
                }
            }
        }
        return ConnectedInputDevices(keyboard, mouse, pads)
    }

    fun forwardTouchButton(button: TouchButtonConfig, pressed: Boolean) {
        val type = if (button.isMouseButton) 2 else 1
        NoxerNativeBridge.pushInputEvent(type, button.glfwCode, if (pressed) 1 else 0)
    }

    fun forwardKeyEvent(androidKeyCode: Int, action: Int, modifiers: Int = 0) {
        val glfw = androidKeyToGlfw(androidKeyCode)
        if (glfw != 0) {
            NoxerNativeBridge.pushInputEvent(1, glfw, if (action == KeyEvent.ACTION_DOWN) 1 else 0, modifiers = modifiers)
        }
    }

    fun forwardMouseDelta(dx: Float, dy: Float, sensitivity: Float, invertY: Boolean) {
        val sy = if (invertY) -dy else dy
        NoxerNativeBridge.pushInputEvent(3, 0, 2, dx * sensitivity, sy * sensitivity)
    }

    fun forwardMouseScroll(vScroll: Float, hScroll: Float) {
        NoxerNativeBridge.pushInputEvent(4, 0, 2, hScroll, vScroll)
    }

    companion object {
        // Standard GLFW keycodes expected by LWJGL 3 / Minecraft Java
        const val GLFW_KEY_W = 87
        const val GLFW_KEY_A = 65
        const val GLFW_KEY_S = 83
        const val GLFW_KEY_D = 68
        const val GLFW_KEY_E = 69
        const val GLFW_KEY_Q = 81
        const val GLFW_KEY_SPACE = 32
        const val GLFW_KEY_LEFT_SHIFT = 340
        const val GLFW_KEY_LEFT_CONTROL = 341
        const val GLFW_KEY_ESCAPE = 256
        const val GLFW_KEY_F3 = 292
        const val GLFW_KEY_F5 = 294
        const val GLFW_KEY_T = 84
        const val GLFW_MOUSE_BUTTON_LEFT = 0
        const val GLFW_MOUSE_BUTTON_RIGHT = 1

        fun defaultTouchButtons(): List<TouchButtonConfig> = listOf(
            TouchButtonConfig("btn_w", "W", 0.12f, 0.56f, 56f, 0.72f, GLFW_KEY_W),
            TouchButtonConfig("btn_a", "A", 0.06f, 0.69f, 56f, 0.72f, GLFW_KEY_A),
            TouchButtonConfig("btn_s", "S", 0.12f, 0.82f, 56f, 0.72f, GLFW_KEY_S),
            TouchButtonConfig("btn_d", "D", 0.18f, 0.69f, 56f, 0.72f, GLFW_KEY_D),
            TouchButtonConfig("btn_jump", "JUMP", 0.86f, 0.76f, 64f, 0.78f, GLFW_KEY_SPACE),
            TouchButtonConfig("btn_sneak", "SHFT", 0.76f, 0.82f, 54f, 0.70f, GLFW_KEY_LEFT_SHIFT),
            TouchButtonConfig("btn_atk", "ATK", 0.87f, 0.54f, 60f, 0.80f, GLFW_MOUSE_BUTTON_LEFT, isMouseButton = true),
            TouchButtonConfig("btn_use", "USE", 0.77f, 0.60f, 56f, 0.75f, GLFW_MOUSE_BUTTON_RIGHT, isMouseButton = true),
            TouchButtonConfig("btn_inv", "INV", 0.66f, 0.84f, 50f, 0.68f, GLFW_KEY_E),
            TouchButtonConfig("btn_esc", "ESC", 0.06f, 0.12f, 48f, 0.68f, GLFW_KEY_ESCAPE),
            TouchButtonConfig("btn_f3", "F3", 0.15f, 0.12f, 48f, 0.65f, GLFW_KEY_F3),
            TouchButtonConfig("btn_chat", "CHAT", 0.24f, 0.12f, 48f, 0.65f, GLFW_KEY_T),
        )

        fun defaultKeybinds(): List<KeybindMapping> = listOf(
            KeybindMapping("Forward", KeyEvent.KEYCODE_W, GLFW_KEY_W, "W"),
            KeybindMapping("Strafe Left", KeyEvent.KEYCODE_A, GLFW_KEY_A, "A"),
            KeybindMapping("Backward", KeyEvent.KEYCODE_S, GLFW_KEY_S, "S"),
            KeybindMapping("Strafe Right", KeyEvent.KEYCODE_D, GLFW_KEY_D, "D"),
            KeybindMapping("Jump", KeyEvent.KEYCODE_SPACE, GLFW_KEY_SPACE, "SPACE"),
            KeybindMapping("Sneak", KeyEvent.KEYCODE_SHIFT_LEFT, GLFW_KEY_LEFT_SHIFT, "L-SHIFT"),
            KeybindMapping("Sprint", KeyEvent.KEYCODE_CTRL_LEFT, GLFW_KEY_LEFT_CONTROL, "L-CTRL"),
            KeybindMapping("Inventory", KeyEvent.KEYCODE_E, GLFW_KEY_E, "E"),
            KeybindMapping("Drop Item", KeyEvent.KEYCODE_Q, GLFW_KEY_Q, "Q"),
            KeybindMapping("Debug Overlay", KeyEvent.KEYCODE_F3, GLFW_KEY_F3, "F3"),
            KeybindMapping("Perspective", KeyEvent.KEYCODE_F5, GLFW_KEY_F5, "F5"),
        )

        fun androidKeyToGlfw(keyCode: Int): Int = when (keyCode) {
            KeyEvent.KEYCODE_W -> GLFW_KEY_W
            KeyEvent.KEYCODE_A -> GLFW_KEY_A
            KeyEvent.KEYCODE_S -> GLFW_KEY_S
            KeyEvent.KEYCODE_D -> GLFW_KEY_D
            KeyEvent.KEYCODE_E -> GLFW_KEY_E
            KeyEvent.KEYCODE_Q -> GLFW_KEY_Q
            KeyEvent.KEYCODE_T -> GLFW_KEY_T
            KeyEvent.KEYCODE_SPACE -> GLFW_KEY_SPACE
            KeyEvent.KEYCODE_SHIFT_LEFT -> GLFW_KEY_LEFT_SHIFT
            KeyEvent.KEYCODE_CTRL_LEFT -> GLFW_KEY_LEFT_CONTROL
            KeyEvent.KEYCODE_ESCAPE -> GLFW_KEY_ESCAPE
            KeyEvent.KEYCODE_F3 -> GLFW_KEY_F3
            KeyEvent.KEYCODE_F5 -> GLFW_KEY_F5
            else -> if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                48 + (keyCode - KeyEvent.KEYCODE_0)
            } else 0
        }
    }
}
