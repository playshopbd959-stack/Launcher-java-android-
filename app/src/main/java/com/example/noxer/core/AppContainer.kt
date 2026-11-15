package com.example.noxer.core

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.noxer.auth.AccountSessionManager
import com.example.noxer.auth.MicrosoftAuthManager
import com.example.noxer.auth.OfflineProfileManager
import com.example.noxer.console.ConsoleLogger
import com.example.noxer.data.db.NoxerDatabase
import com.example.noxer.download.DownloadEngine
import com.example.noxer.graphics.RendererManager
import com.example.noxer.input.InputManager
import com.example.noxer.installation.InstallationManager
import com.example.noxer.launch.CrashHandler
import com.example.noxer.launch.GameLauncher
import com.example.noxer.mods.ModLoaderManager
import com.example.noxer.runtime.JavaRuntimeManager
import com.example.noxer.runtime.RuntimeVerificationService
import com.example.noxer.settings.SettingsManager
import com.example.noxer.storage.StorageManager
import com.example.noxer.version.MojangManifestApi
import com.example.noxer.version.VersionCenterRepository
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `offline_profiles` (
                `id` TEXT NOT NULL,
                `username` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `iconId` TEXT NOT NULL DEFAULT 'SHIELD',
                `notes` TEXT NOT NULL DEFAULT '',
                `createdAt` INTEGER NOT NULL,
                `lastUsedAt` INTEGER NOT NULL,
                `preferredInstallationId` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE runtimes ADD COLUMN `javaBinPath` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE runtimes ADD COLUMN `sourceProvider` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE runtimes ADD COLUMN `isDefault` INTEGER NOT NULL DEFAULT 0")
    }
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val paths: NoxerPaths = NoxerPaths(appContext)

    val database: NoxerDatabase = Room.databaseBuilder(
        appContext,
        NoxerDatabase::class.java,
        "noxer_launcher.db",
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    val consoleLogger: ConsoleLogger = ConsoleLogger(database.consoleLogDao(), paths)
    val settingsManager: SettingsManager = SettingsManager(appContext)
    val storageManager: StorageManager = StorageManager(appContext, paths, consoleLogger)
    val rendererManager: RendererManager = RendererManager(appContext)
    val inputManager: InputManager = InputManager(appContext, database.controlProfileDao(), json)
    val downloadEngine: DownloadEngine = DownloadEngine(okHttpClient, consoleLogger)
    val mojangApi: MojangManifestApi = MojangManifestApi(okHttpClient, json)

    val versionRepository: VersionCenterRepository = VersionCenterRepository(
        api = mojangApi,
        cachedVersionDao = database.cachedVersionDao(),
        installationDao = database.installationDao(),
        paths = paths,
        json = json,
        consoleLogger = consoleLogger,
    )

    val modLoaderManager: ModLoaderManager = ModLoaderManager(
        okHttpClient = okHttpClient,
        json = json,
        paths = paths,
        downloadEngine = downloadEngine,
        consoleLogger = consoleLogger,
    )

    val installationManager: InstallationManager = InstallationManager(
        installationDao = database.installationDao(),
        versionRepository = versionRepository,
        downloadEngine = downloadEngine,
        modLoaderManager = modLoaderManager,
        paths = paths,
        consoleLogger = consoleLogger,
    )

    val authManager: MicrosoftAuthManager = MicrosoftAuthManager(
        okHttpClient = okHttpClient,
        json = json,
        accountDao = database.accountDao(),
        consoleLogger = consoleLogger,
    )

    val offlineProfileManager: OfflineProfileManager = OfflineProfileManager(
        offlineProfileDao = database.offlineProfileDao(),
        consoleLogger = consoleLogger,
    )

    val accountSessionManager: AccountSessionManager = AccountSessionManager(
        authManager = authManager,
        offlineProfileManager = offlineProfileManager,
        settingsManager = settingsManager,
        consoleLogger = consoleLogger,
    )

    val runtimeManager: JavaRuntimeManager = JavaRuntimeManager(
        okHttpClient = okHttpClient,
        json = json,
        runtimeDao = database.runtimeDao(),
        paths = paths,
        consoleLogger = consoleLogger,
        installationDao = database.installationDao(),
        executionProbe = RuntimeVerificationService.createProbe(appContext),
    )

    val crashHandler: CrashHandler = CrashHandler(database.crashReportDao(), paths)

    val gameLauncher: GameLauncher = GameLauncher(
        context = appContext,
        paths = paths,
        installationDao = database.installationDao(),
        runtimeDao = database.runtimeDao(),
        runtimeManager = runtimeManager,
        authManager = authManager,
        offlineProfileManager = offlineProfileManager,
        accountSessionManager = accountSessionManager,
        downloadEngine = downloadEngine,
        modLoaderManager = modLoaderManager,
        rendererManager = rendererManager,
        crashHandler = crashHandler,
        consoleLogger = consoleLogger,
        json = json,
    )
}
