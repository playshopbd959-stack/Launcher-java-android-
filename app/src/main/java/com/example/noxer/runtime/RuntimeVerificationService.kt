package com.example.noxer.runtime

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.example.noxer.nativebridge.NoxerNativeBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * Isolated verification service declared with `android:process=":jvm_verify"` in AndroidManifest.xml.
 * Loading HotSpot libjvm.so and calling JNI_CreateJavaVM inside a dedicated worker process guarantees:
 * 1. Real verification that Java executes on the host Android device.
 * 2. Clean isolation so verifying a runtime never consumes the main UI process's single HotSpot JVM slot
 *    or risks destabilizing the launcher UI if a third-party runtime is broken.
 */
class RuntimeVerificationService : Service() {

    private class IncomingHandler(
        private val service: RuntimeVerificationService,
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != MSG_VERIFY_JVM) {
                super.handleMessage(msg)
                return
            }
            val replyTo = msg.replyTo
            val data = msg.data
            val libJvmPath = data.getString(KEY_LIBJVM_PATH).orEmpty()
            val javaHome = data.getString(KEY_JAVA_HOME).orEmpty()

            Thread {
                val result = NoxerNativeBridge.verifyJvmExecution(libJvmPath, javaHome)
                val reply = Message.obtain(null, MSG_VERIFY_RESULT).apply {
                    this.data = Bundle().apply {
                        putString(KEY_RESULT, result)
                    }
                }
                runCatching { replyTo?.send(reply) }
                Handler(Looper.getMainLooper()).postDelayed({
                    service.stopSelf()
                    exitProcess(0)
                }, 150L)
            }.start()
        }
    }

    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        messenger = Messenger(IncomingHandler(this))
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    companion object {
        private const val MSG_VERIFY_JVM = 1
        private const val MSG_VERIFY_RESULT = 2
        private const val KEY_LIBJVM_PATH = "libjvm_path"
        private const val KEY_JAVA_HOME = "java_home"
        private const val KEY_RESULT = "result"

        fun createProbe(appContext: Context): RuntimeExecutionProbe = RuntimeExecutionProbe { libJvmPath, javaHome ->
            withContext(Dispatchers.Main) {
                val deferred = CompletableDeferred<String>()
                val replyHandler = object : Handler(Looper.getMainLooper()) {
                    override fun handleMessage(msg: Message) {
                        if (msg.what == MSG_VERIFY_RESULT) {
                            val res = msg.data.getString(KEY_RESULT) ?: "ERROR: Empty reply from verification service"
                            deferred.complete(res)
                        }
                    }
                }
                val replyMessenger = Messenger(replyHandler)
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        val remote = Messenger(service)
                        val msg = Message.obtain(null, MSG_VERIFY_JVM).apply {
                            replyTo = replyMessenger
                            data = Bundle().apply {
                                putString(KEY_LIBJVM_PATH, libJvmPath)
                                putString(KEY_JAVA_HOME, javaHome)
                            }
                        }
                        runCatching { remote.send(msg) }.onFailure { err ->
                            deferred.complete("ERROR: Failed sending verify request: ${err.message}")
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        if (!deferred.isCompleted) {
                            deferred.complete("ERROR: Verification process terminated unexpectedly (incompatible binary or missing dependency)")
                        }
                    }
                }

                val intent = Intent(appContext, RuntimeVerificationService::class.java)
                val bound = runCatching {
                    appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                }.getOrDefault(false)

                if (!bound) {
                    return@withContext "ERROR: Could not bind RuntimeVerificationService"
                }

                try {
                    withTimeoutOrNull(18_000L) {
                        deferred.await()
                    } ?: "ERROR: Runtime verification timed out after 18s"
                } finally {
                    runCatching { appContext.unbindService(connection) }
                }
            }
        }
    }
}
