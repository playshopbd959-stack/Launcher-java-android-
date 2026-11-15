package com.example.noxer.graphics

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import com.example.noxer.nativebridge.NoxerNativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RendererBackend(
    val id: String,
    val displayName: String,
    val glTarget: String,
    val description: String,
    val shaderWarning: String?,
) {
    GL4ES_GLES3(
        id = "GL4ES_GLES3",
        displayName = "GL4ES (OpenGL 2.1 -> GLES 3.0)",
        glTarget = "OpenGL 2.1 compat",
        description = "Maximum compatibility for Minecraft 1.8.9–1.20.4 fixed-function & legacy shaders.",
        shaderWarning = "Complex OptiFine/Iris deferred PBR shaders are not supported on GL4ES.",
    ),
    HOLY_GL4ES(
        id = "HOLY_GL4ES",
        displayName = "Holy-GL4ES (Shader Patch)",
        glTarget = "OpenGL 3.2 core compat",
        description = "Extended GLSL translation for modern 1.17+ Blaze3D core shaders.",
        shaderWarning = "Heavy shaderpacks may drop frames or require Vulkan/Zink.",
    ),
    ANGLE_ZINK_VULKAN(
        id = "ANGLE_ZINK_VULKAN",
        displayName = "Zink / ANGLE (Vulkan)",
        glTarget = "OpenGL 4.6 over Vulkan",
        description = "Translates desktop OpenGL directly to Vulkan 1.1+ when supported by GPU drivers.",
        shaderWarning = "Requires Vulkan 1.1+ driver extensions (VK_KHR_maintenance2); Adreno/Mali quirks may apply.",
    ),
    NATIVE_GLES3(
        id = "NATIVE_GLES3",
        displayName = "Native OpenGL ES 3.0",
        glTarget = "GLES 3.0 direct",
        description = "Direct Android EGL/GLES3 surface pipeline used by the Noxer viewport & native diagnostics.",
        shaderWarning = "Desktop GL desktop-only calls require GL4ES or Zink translation.",
    ),
}

data class GraphicsDiagnostics(
    val glVendor: String = "Probing...",
    val glRenderer: String = "Probing...",
    val glVersion: String = "Probing...",
    val eglVersion: String = "Unknown",
    val gles3Supported: Boolean = false,
    val s3tcSupported: Boolean = false,
    val astcSupported: Boolean = false,
    val rawReport: String = "",
)

data class LivePerformanceMetrics(
    val measuredFps: Int = 0,
    val frameTimeMs: Float = 0f,
    val javaHeapUsedMb: Long = 0L,
    val javaHeapMaxMb: Long = 0L,
    val nativePssMb: Long = 0L,
    val deviceFreeRamMb: Long = 0L,
    val deviceTotalRamMb: Long = 0L,
    val processStatus: String = "Idle",
)

class RendererManager(private val context: Context) {
    private val _diagnostics = MutableStateFlow(probeDiagnostics())
    val diagnostics: StateFlow<GraphicsDiagnostics> = _diagnostics.asStateFlow()

    private val _metrics = MutableStateFlow(sampleRealMemoryMetrics("Idle"))
    val metrics: StateFlow<LivePerformanceMetrics> = _metrics.asStateFlow()

    fun refreshDiagnostics() {
        _diagnostics.value = probeDiagnostics()
    }

    fun updateFrameMetrics(fps: Int, frameTimeMs: Float, processStatus: String) {
        val mem = sampleRealMemoryMetrics(processStatus)
        _metrics.value = mem.copy(measuredFps = fps, frameTimeMs = frameTimeMs)
    }

    fun sampleMemorySnapshot(processStatus: String = _metrics.value.processStatus) {
        _metrics.value = _metrics.value.let { current ->
            val mem = sampleRealMemoryMetrics(processStatus)
            mem.copy(measuredFps = current.measuredFps, frameTimeMs = current.frameTimeMs)
        }
    }

    private fun sampleRealMemoryMetrics(processStatus: String): LivePerformanceMetrics {
        val rt = Runtime.getRuntime()
        val heapUsed = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
        val heapMax = rt.maxMemory() / (1024L * 1024L)
        val nativePss = Debug.getPss() / 1024L

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        return LivePerformanceMetrics(
            javaHeapUsedMb = heapUsed,
            javaHeapMaxMb = heapMax,
            nativePssMb = nativePss,
            deviceFreeRamMb = memInfo.availMem / (1024L * 1024L),
            deviceTotalRamMb = memInfo.totalMem / (1024L * 1024L),
            processStatus = processStatus,
        )
    }

    private fun probeDiagnostics(): GraphicsDiagnostics {
        val raw = NoxerNativeBridge.probeEglCapabilities()
        val map = raw.lines()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
            }.toMap()
        return GraphicsDiagnostics(
            glVendor = map["GL_VENDOR"] ?: "Unavailable on host JVM",
            glRenderer = map["GL_RENDERER"] ?: "Unavailable on host JVM",
            glVersion = map["GL_VERSION"] ?: "Unavailable on host JVM",
            eglVersion = map["EGL_VERSION"] ?: "Unknown",
            gles3Supported = map["GLES3_SUPPORTED"] == "true",
            s3tcSupported = map["HAS_S3TC"] == "true",
            astcSupported = map["HAS_ASTC"] == "true",
            rawReport = raw,
        )
    }

    fun getEnvironmentVariables(backend: RendererBackend, scalePercent: Int): Map<String, String> = mapOf(
        "NOXER_RENDERER" to backend.id,
        "LIBGL_ES" to "3",
        "LIBGL_MIPMAP" to "3",
        "NOXER_RES_SCALE" to (scalePercent / 100f).toString(),
        "MESA_GL_VERSION_OVERRIDE" to when (backend) {
            RendererBackend.HOLY_GL4ES -> "3.2"
            RendererBackend.ANGLE_ZINK_VULKAN -> "4.6"
            else -> "2.1"
        },
    )
}
