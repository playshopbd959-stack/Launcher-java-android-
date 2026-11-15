package com.example.noxer.console

import com.example.noxer.core.NoxerPaths
import com.example.noxer.data.db.ConsoleLogDao
import com.example.noxer.data.db.ConsoleLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConsoleLogger(
    private val dao: ConsoleLogDao,
    private val paths: NoxerPaths,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val latestLogFile: File = File(paths.launcherLogsDir, "latest.log")
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val logs: Flow<List<ConsoleLogEntity>> = dao.observeRecent()

    fun info(source: String, message: String) = log("INFO", source, message)
    fun warn(source: String, message: String) = log("WARN", source, message)
    fun error(source: String, message: String) = log("ERROR", source, message)
    fun debug(source: String, message: String) = log("DEBUG", source, message)

    fun log(level: String, source: String, message: String) {
        val ts = System.currentTimeMillis()
        scope.launch {
            dao.insert(
                ConsoleLogEntity(
                    timestamp = ts,
                    level = level,
                    source = source,
                    message = message,
                )
            )
            runCatching {
                latestLogFile.parentFile?.mkdirs()
                val formatted = "[${dateFormat.format(Date(ts))}] [$level/$source] $message\n"
                latestLogFile.appendText(formatted)
            }
        }
    }

    suspend fun clear() {
        dao.clearAll()
        runCatching {
            if (latestLogFile.exists()) latestLogFile.writeText("")
        }
    }

    fun getLatestLogFile(): File = latestLogFile
}
