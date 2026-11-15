package com.example.noxer.nativebridge

import android.view.Surface

interface NativeLogCallback {
    fun onLogLine(line: String, isError: Boolean)
}

/**
 * JNI bridge to libnoxer_native.so (C++17 / EGL / OpenGL ES 3 / libjvm.so dlopen bridge / input ring buffer).
 * Gracefully degrades with explicit diagnostics on JVM unit tests (Robolectric) where native .so is absent.
 */
object NoxerNativeBridge {
    private var nativeLoaded = false
    private var loadError: String? = null

    init {
        try {
            System.loadLibrary("noxer_native")
            nativeLoaded = true
        } catch (t: Throwable) {
            nativeLoaded = false
            loadError = t.message ?: t.javaClass.simpleName
        }
    }

    fun isNativeLibraryLoaded(): Boolean = nativeLoaded
    fun getLoadError(): String? = loadError

    fun getArchitecture(): String =
        if (nativeLoaded) nativeGetArchitecture() else System.getProperty("os.arch") ?: "unknown"

    fun probeEglCapabilities(): String =
        if (nativeLoaded) nativeProbeEglCapabilities()
        else "NATIVE_UNAVAILABLE: ${loadError ?: "libnoxer_native.so not loaded"}"

    fun initWindow(surface: Surface): Boolean =
        if (nativeLoaded) nativeInitWindow(surface) else false

    fun resizeWindow(width: Int, height: Int) {
        if (nativeLoaded) nativeResizeWindow(width, height)
    }

    fun renderHeartbeatFrame(pulse: Float) {
        if (nativeLoaded) nativeRenderHeartbeatFrame(pulse)
    }

    fun destroyWindow() {
        if (nativeLoaded) nativeDestroyWindow()
    }

    fun pushInputEvent(type: Int, code: Int, action: Int, x: Float = 0f, y: Float = 0f, modifiers: Int = 0) {
        if (nativeLoaded) nativePushInputEvent(type, code, action, x, y, modifiers)
    }

    fun getPendingInputCount(): Int =
        if (nativeLoaded) nativeGetPendingInputCount() else 0

    fun setEnv(key: String, value: String) {
        if (nativeLoaded) nativeSetEnv(key, value)
    }

    fun probeLibJvm(libJvmPath: String): String =
        if (nativeLoaded) nativeProbeLibJvm(libJvmPath)
        else "NATIVE_UNAVAILABLE: ${loadError ?: "libnoxer_native.so not loaded"}"

    fun verifyJvmExecution(libJvmPath: String, javaHome: String): String =
        if (nativeLoaded) nativeVerifyJvmExecution(libJvmPath, javaHome)
        else "NATIVE_UNAVAILABLE: ${loadError ?: "libnoxer_native.so not loaded"}"

    fun launchJvm(
        libJvmPath: String,
        jvmOptions: Array<String>,
        mainClass: String,
        gameArgs: Array<String>,
        callback: NativeLogCallback,
    ): Int {
        if (!nativeLoaded) {
            callback.onLogLine("[NoxerNativeBridge] Native library not loaded: ${loadError ?: "unknown"}", true)
            return -100
        }
        return nativeLaunchJvm(libJvmPath, jvmOptions, mainClass, gameArgs, callback)
    }

    private external fun nativeGetArchitecture(): String
    private external fun nativeProbeEglCapabilities(): String
    private external fun nativeInitWindow(surface: Surface): Boolean
    private external fun nativeResizeWindow(width: Int, height: Int)
    private external fun nativeRenderHeartbeatFrame(pulse: Float)
    private external fun nativeDestroyWindow()
    private external fun nativePushInputEvent(type: Int, code: Int, action: Int, x: Float, y: Float, modifiers: Int)
    private external fun nativeGetPendingInputCount(): Int
    private external fun nativeSetEnv(key: String, value: String)
    private external fun nativeProbeLibJvm(libJvmPath: String): String
    private external fun nativeVerifyJvmExecution(libJvmPath: String, javaHome: String): String
    private external fun nativeLaunchJvm(
        libJvmPath: String,
        jvmOptions: Array<String>,
        mainClass: String,
        gameArgs: Array<String>,
        callback: NativeLogCallback,
    ): Int
}
