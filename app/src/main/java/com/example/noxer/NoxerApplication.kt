package com.example.noxer

import android.app.Application
import com.example.noxer.core.AppContainer
import com.example.noxer.nativebridge.NoxerNativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NoxerApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            container.inputManager.ensureDefaultProfile()
            container.consoleLogger.info(
                "Core",
                "NOXER LAUNCHER initialized (arch=${NoxerNativeBridge.getArchitecture()}, nativeLoaded=${NoxerNativeBridge.isNativeLibraryLoaded()})",
            )
        }
    }
}
