package com.example.noxer.launch

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.noxer.NoxerApplication
import com.example.noxer.core.AppContainer
import com.example.noxer.input.InputManager
import com.example.noxer.input.TouchButtonConfig
import com.example.noxer.nativebridge.NativeLogCallback
import com.example.noxer.nativebridge.NoxerNativeBridge
import com.example.ui.theme.NoxerBlack
import com.example.ui.theme.NoxerBorder
import com.example.ui.theme.NoxerCrimsonGlow
import com.example.ui.theme.NoxerEmerald
import com.example.ui.theme.NoxerLauncherTheme
import com.example.ui.theme.NoxerNeonRed
import com.example.ui.theme.NoxerSurface
import com.example.ui.theme.NoxerTextPrimary
import com.example.ui.theme.NoxerTextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

class GameViewportActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INSTANCE_ID = "instance_id"
        const val EXTRA_PLAN_PATH = "plan_path"
        private const val TAG = "NoxerGameViewport"
    }

    private var surfaceViewRef: SurfaceView? = null
    private var pointerCaptured by mutableStateOf(false)
    private val jvmStarted = AtomicBoolean(false)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activePlan: LaunchExecutionPlan? = null
    private var isBootingJvm by mutableStateOf(true)
    private var gameOwnsEgl by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Initializing EGL / OpenGL ES 3.0 viewport...")
    private var errorTitle by mutableStateOf<String?>(null)
    private var errorMessage by mutableStateOf<String?>(null)
    private var recoveryHint by mutableStateOf<String?>(null)
    private val liveLogLines = mutableStateListOf<String>()

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDragged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val container = (application as NoxerApplication).container
        val planPath = intent.getStringExtra(EXTRA_PLAN_PATH).orEmpty()
        activePlan = if (planPath.isNotBlank() && File(planPath).exists()) {
            runCatching {
                container.json.decodeFromString(LaunchExecutionPlan.serializer(), File(planPath).readText())
            }.getOrNull()
        } else null

        if (activePlan == null) {
            isBootingJvm = false
            errorTitle = "Missing Launch Execution Plan"
            errorMessage = "Unable to load game launch configuration ($planPath)."
            recoveryHint = "Return to launcher and press PLAY again."
        } else {
            appendLog("[Viewport] Loaded ${activePlan!!.installationName} (MC ${activePlan!!.minecraftVersionId}, Java ${activePlan!!.runtimeMajorVersion} ${activePlan!!.runtimeAbi})", false)
        }

        setContent {
            NoxerLauncherTheme {
                val metrics by container.rendererManager.metrics.collectAsStateWithLifecycle()
                val profiles by container.inputManager.profiles.collectAsStateWithLifecycle(initialValue = emptyList())

                var showConsole by remember { mutableStateOf(false) }
                var editTouchLayout by remember { mutableStateOf(false) }
                var buttons by remember(profiles) {
                    mutableStateOf(
                        profiles.firstOrNull()?.let { container.inputManager.decodeTouchButtons(it.touchButtonsJson) }
                            ?: InputManager.defaultTouchButtons()
                    )
                }

                // Real Choreographer heartbeat while awaiting LWJGL context ownership
                var measuredFps by remember { mutableIntStateOf(0) }
                DisposableEffect(Unit) {
                    val choreographer = Choreographer.getInstance()
                    var frameCount = 0
                    var windowStartNs = System.nanoTime()
                    var lastFrameNs = windowStartNs
                    val callback = object : Choreographer.FrameCallback {
                        override fun doFrame(frameTimeNanos: Long) {
                            frameCount++
                            val frameMs = (frameTimeNanos - lastFrameNs) / 1_000_000f
                            lastFrameNs = frameTimeNanos

                            val owns = NoxerNativeBridge.isGameOwnsEgl()
                            if (owns != gameOwnsEgl) {
                                gameOwnsEgl = owns
                                if (owns) isBootingJvm = false
                            }
                            if (!owns && errorMessage == null) {
                                val pulse = ((frameTimeNanos / 1_000_000_000.0) % 2.0).toFloat()
                                val normPulse = if (pulse > 1f) 2f - pulse else pulse
                                NoxerNativeBridge.renderHeartbeatFrame(normPulse)
                            }

                            val elapsed = frameTimeNanos - windowStartNs
                            if (elapsed >= 1_000_000_000L) {
                                measuredFps = frameCount
                                container.rendererManager.updateFrameMetrics(
                                    fps = frameCount,
                                    frameTimeMs = frameMs,
                                    processStatus = if (owns) "Minecraft Active" else statusMessage,
                                )
                                frameCount = 0
                                windowStartNs = frameTimeNanos
                            }
                            choreographer.postFrameCallback(this)
                        }
                    }
                    choreographer.postFrameCallback(callback)
                    onDispose { choreographer.removeFrameCallback(callback) }
                }

                Box(modifier = Modifier.fillMaxSize().background(NoxerBlack)) {
                    // Real EGL / OpenGL ES 3.0 SurfaceView bound to libnoxer_native.so + libglfw.so + libGL.so
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                isFocusable = true
                                isFocusableInTouchMode = true
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        val ok = NoxerNativeBridge.initWindow(holder.surface)
                                        if (ok) {
                                            appendLog("[EGL] Window surface & GLES3 context initialized.", false)
                                            maybeStartJvm(container)
                                        } else {
                                            isBootingJvm = false
                                            errorTitle = "EGL Surface Error"
                                            errorMessage = "Native EGL window creation failed on this device."
                                            recoveryHint = "Check Settings -> Renderer backend or device GPU drivers."
                                        }
                                    }

                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                        NoxerNativeBridge.resizeWindow(width, height)
                                    }

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        NoxerNativeBridge.destroyWindow()
                                    }
                                })
                                setOnTouchListener { _, event ->
                                    if (!editTouchLayout && !pointerCaptured) {
                                        handleSurfaceTouch(event, container.inputManager)
                                        true
                                    } else {
                                        false
                                    }
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    setOnCapturedPointerListener { _, event ->
                                        handleMouseMotion(event, container.inputManager)
                                        true
                                    }
                                }
                                surfaceViewRef = this
                            }
                        },
                    )

                    // Touch Controls Overlay (Movable when Edit mode active)
                    TouchOverlayLayer(
                        buttons = buttons,
                        editMode = editTouchLayout,
                        onPressChanged = { btn, pressed ->
                            container.inputManager.forwardTouchButton(btn, pressed)
                        },
                        onMoveButton = { id, newX, newY ->
                            buttons = buttons.map {
                                if (it.id == id) it.copy(xFraction = newX, yFraction = newY) else it
                            }
                        },
                    )

                    // Top HUD Strip (Real Metrics & Controls)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .safeDrawingPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = NoxerSurface.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NoxerBorder),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "NOXER ENGINE",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = NoxerNeonRed,
                                )
                                Text(
                                    text = "$measuredFps FPS • ${metrics.javaHeapUsedMb}/${metrics.javaHeapMaxMb} MB Heap • ${if (gameOwnsEgl) "GLES3 Live" else "Booting JVM"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NoxerTextPrimary,
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        if (pointerCaptured) {
                                            surfaceViewRef?.releasePointerCapture()
                                            pointerCaptured = false
                                        } else {
                                            surfaceViewRef?.requestFocus()
                                            surfaceViewRef?.requestPointerCapture()
                                            pointerCaptured = true
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (pointerCaptured) NoxerNeonRed else NoxerSurface.copy(alpha = 0.85f)
                                ),
                            ) {
                                Icon(Icons.Default.Mouse, contentDescription = "Pointer Capture", modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (pointerCaptured) "Captured" else "Capture Mouse", style = MaterialTheme.typography.labelSmall)
                            }
                            Button(
                                onClick = { editTouchLayout = !editTouchLayout },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (editTouchLayout) NoxerNeonRed else NoxerSurface.copy(alpha = 0.85f)
                                ),
                            ) {
                                Text(if (editTouchLayout) "Lock HUD" else "Move HUD", style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(
                                onClick = { showConsole = !showConsole },
                                modifier = Modifier
                                    .background(NoxerSurface.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                                    .testTag("viewport_console_toggle"),
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = "Toggle Console", tint = NoxerTextPrimary)
                            }
                            IconButton(
                                onClick = { finishAndTerminateGameProcess() },
                                modifier = Modifier
                                    .background(NoxerNeonRed.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                                    .testTag("viewport_exit_button"),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Exit Viewport", tint = Color.White)
                            }
                        }
                    }

                    // Booting or JVM Exit Error Card (auto-hides as soon as Minecraft claims EGL context)
                    if (errorMessage != null || (isBootingJvm && !gameOwnsEgl)) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp)
                                .fillMaxWidth(0.75f),
                            color = NoxerSurface.copy(alpha = 0.94f),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (errorMessage != null) NoxerNeonRed else NoxerBorder,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = errorTitle ?: "Starting ${activePlan?.installationName ?: "Minecraft"}",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = if (errorMessage != null) NoxerCrimsonGlow else NoxerEmerald,
                                )
                                Text(
                                    text = errorMessage ?: statusMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NoxerTextPrimary,
                                )
                                activePlan?.authModeLabel?.takeIf { it.isNotBlank() }?.let { authLabel ->
                                    Text(
                                        text = authLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NoxerTextSecondary,
                                    )
                                }
                                recoveryHint?.let { rec ->
                                    Text(
                                        text = "Recovery: $rec",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NoxerTextSecondary,
                                    )
                                }
                                if (errorMessage != null) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(onClick = { showConsole = true }) {
                                            Text("Inspect Logs")
                                        }
                                        Button(
                                            onClick = { finishAndTerminateGameProcess() },
                                            colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                                        ) {
                                            Text("Return to Launcher")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Expandable Live Console Overlay
                    if (showConsole) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(220.dp)
                                .padding(12.dp),
                            color = NoxerBlack.copy(alpha = 0.92f),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NoxerBorder),
                        ) {
                            LazyColumn(modifier = Modifier.padding(10.dp), reverseLayout = true) {
                                items(liveLogLines.take(120)) { line ->
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (line.contains("[ERR]") || line.contains("Exception", ignoreCase = true)) {
                                            NoxerCrimsonGlow
                                        } else {
                                            NoxerTextSecondary
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun maybeStartJvm(container: AppContainer) {
        val plan = activePlan ?: return
        if (!jvmStarted.compareAndSet(false, true)) return

        statusMessage = "Starting Java ${plan.runtimeMajorVersion} VM (${plan.runtimeAbi})..."
        val logWriter = runCatching {
            val logFile = File(plan.logFilePath)
            logFile.parentFile?.mkdirs()
            PrintWriter(FileWriter(logFile, false), true)
        }.getOrNull()

        Thread({
            try {
                plan.envVars.forEach { (k, v) -> NoxerNativeBridge.setEnv(k, v) }
                val probe = NoxerNativeBridge.probeLibJvm(plan.libJvmPath)
                val probeLine = "[JVM] libjvm.so probe: $probe"
                appendLog(probeLine, false)
                logWriter?.println(probeLine)

                runOnUiThread {
                    statusMessage = "Executing ${plan.mainClass} (Minecraft ${plan.minecraftVersionId})..."
                }

                val captured = mutableListOf<String>()
                val exitCode = NoxerNativeBridge.launchJvm(
                    libJvmPath = plan.libJvmPath,
                    jvmOptions = plan.jvmOptions.toTypedArray(),
                    mainClass = plan.mainClass,
                    gameArgs = plan.gameArgs.toTypedArray(),
                    callback = object : NativeLogCallback {
                        override fun onLogLine(line: String, isError: Boolean) {
                            val tagLine = if (isError) "[ERR] $line" else line
                            synchronized(captured) {
                                if (captured.size > 400) captured.removeAt(0)
                                captured += tagLine
                            }
                            logWriter?.println(tagLine)
                            appendLog(tagLine, isError)
                        }
                    },
                )

                logWriter?.flush()
                logWriter?.close()

                if (exitCode != 0) {
                    val cat = when {
                        probe.contains("DLOPEN_FAILED", ignoreCase = true) || exitCode == -102 ->
                            LaunchErrorCategory.UNSUPPORTED_ARCHITECTURE
                        exitCode == -105 || exitCode == -106 ->
                            LaunchErrorCategory.MISSING_LIBRARIES
                        else -> LaunchErrorCategory.FAILED_GAME_LAUNCH
                    }
                    ioScope.launch {
                        container.crashHandler.recordCrash(
                            installationId = plan.installationId,
                            installationName = plan.installationName,
                            minecraftVersion = plan.minecraftVersionId,
                            exitCode = exitCode,
                            rawLogs = synchronized(captured) { captured.toList() },
                            overrideCategory = cat,
                            explicitSummary = "Game JVM exited with code $exitCode. $probe",
                        )
                    }
                    runOnUiThread {
                        isBootingJvm = false
                        errorTitle = cat.title
                        errorMessage = "Game JVM exited with code $exitCode.\n$probe"
                        recoveryHint = CrashHandler.defaultRecoveryFor(cat)
                    }
                } else {
                    runOnUiThread { finishAndTerminateGameProcess() }
                }
            } catch (t: Throwable) {
                logWriter?.println("[FATAL] ${t.stackTraceToString()}")
                logWriter?.close()
                runOnUiThread {
                    isBootingJvm = false
                    errorTitle = "JVM Launch Exception"
                    errorMessage = t.message ?: "Unexpected native JVM error"
                    recoveryHint = "Check Logs overlay and verify selected runtime."
                }
            }
        }, "NoxerJvmMain").start()
    }

    private fun appendLog(line: String, isError: Boolean) {
        if (isError) Log.e(TAG, line) else Log.i(TAG, line)
        runOnUiThread {
            if (liveLogLines.size >= 400) {
                liveLogLines.removeAt(liveLogLines.lastIndex)
            }
            liveLogLines.add(0, line)
        }
    }

    private fun handleSurfaceTouch(event: MotionEvent, inputManager: InputManager) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                touchDragged = false
                NoxerNativeBridge.pushInputEvent(3, 0, 2, event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                if (abs(event.x - touchDownX) > 10f || abs(event.y - touchDownY) > 10f) {
                    touchDragged = true
                }
                lastTouchX = event.x
                lastTouchY = event.y
                // Send cursor coordinates + delta nicely
                NoxerNativeBridge.pushInputEvent(3, 0, 2, event.x, event.y)
                if (touchDragged && (dx != 0f || dy != 0f)) {
                    inputManager.forwardMouseDelta(dx, dy, 1.0f, false)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!touchDragged) {
                    // Tap acts as left mouse click for menus / interaction
                    NoxerNativeBridge.pushInputEvent(2, InputManager.GLFW_MOUSE_BUTTON_LEFT, 1, event.x, event.y)
                    NoxerNativeBridge.pushInputEvent(2, InputManager.GLFW_MOUSE_BUTTON_LEFT, 0, event.x, event.y)
                }
            }
        }
    }

    private fun finishAndTerminateGameProcess() {
        finish()
        // Kill only this isolated :game process so HotSpot JVM unloads completely for clean relaunch
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val container = (application as NoxerApplication).container
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            container.inputManager.forwardKeyEvent(event.keyCode, event.action, event.metaState)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val container = (application as NoxerApplication).container
        if (event.source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE) {
            handleMouseMotion(event, container.inputManager)
            return true
        }
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
            val axisX = event.getAxisValue(MotionEvent.AXIS_X)
            val axisY = event.getAxisValue(MotionEvent.AXIS_Y)
            NoxerNativeBridge.pushInputEvent(5, 0, 2, axisX, axisY)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun handleMouseMotion(event: MotionEvent, inputManager: InputManager) {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            inputManager.forwardMouseScroll(v, h)
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            inputManager.forwardMouseDelta(event.x, event.y, 1.0f, false)
        }
    }
}

@androidx.compose.runtime.Composable
private fun TouchOverlayLayer(
    buttons: List<TouchButtonConfig>,
    editMode: Boolean,
    onPressChanged: (TouchButtonConfig, Boolean) -> Unit,
    onMoveButton: (String, Float, Float) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxWPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val maxHPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

        buttons.forEach { btn ->
            val sizePx = with(density) { btn.sizeDp.dp.toPx() }
            val xPx = (btn.xFraction * maxWPx - sizePx / 2f).coerceIn(0f, (maxWPx - sizePx).coerceAtLeast(0f))
            val yPx = (btn.yFraction * maxHPx - sizePx / 2f).coerceIn(0f, (maxHPx - sizePx).coerceAtLeast(0f))

            Box(
                modifier = Modifier
                    .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
                    .size(btn.sizeDp.dp)
                    .alpha(btn.opacity)
                    .background(
                        color = if (editMode) NoxerNeonRed.copy(alpha = 0.35f) else NoxerSurface.copy(alpha = 0.78f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = if (editMode) NoxerNeonRed else NoxerBorder,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .pointerInput(btn.id, editMode) {
                        if (editMode) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val nx = ((xPx + dragAmount.x + sizePx / 2f) / maxWPx).coerceIn(0.05f, 0.95f)
                                val ny = ((yPx + dragAmount.y + sizePx / 2f) / maxHPx).coerceIn(0.08f, 0.92f)
                                onMoveButton(btn.id, nx, ny)
                            }
                        } else {
                            var lastPressed = false
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val anyPressed = event.changes.any { it.pressed }
                                    if (anyPressed != lastPressed) {
                                        lastPressed = anyPressed
                                        onPressChanged(btn, anyPressed)
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = btn.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = NoxerTextPrimary,
                )
            }
        }
    }
}
