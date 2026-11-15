package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.noxer.auth.AccountCrypto
import com.example.noxer.auth.AccountSessionManager
import com.example.noxer.auth.ActiveIdentity
import com.example.noxer.auth.MicrosoftAuthManager
import com.example.noxer.auth.OfflineProfileIcon
import com.example.noxer.auth.OfflineProfileManager
import com.example.noxer.auth.OfflineProfileValidator
import com.example.noxer.console.ConsoleLogger
import com.example.noxer.core.NoxerPaths
import com.example.noxer.data.db.AccountEntity
import com.example.noxer.data.db.InstallationEntity
import com.example.noxer.data.db.LoaderType
import com.example.noxer.data.db.NoxerDatabase
import com.example.noxer.download.DownloadEngine
import com.example.noxer.download.MojangRuleEvaluator
import com.example.noxer.graphics.RendererManager
import com.example.noxer.launch.CrashHandler
import com.example.noxer.launch.GameLauncher
import com.example.noxer.launch.LaunchErrorCategory
import com.example.noxer.mods.ModLoaderManager
import com.example.noxer.settings.SettingsManager
import com.example.noxer.version.OsRule
import com.example.noxer.version.RuleEntry
import com.example.noxer.version.VersionCategory
import com.example.noxer.version.VersionCenterRepository
import com.example.noxer.version.VersionDetails
import com.example.noxer.version.VersionManifestV2
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NoxerCoreTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun manifestParsingAndVersionCategoriesWork() {
        val sampleManifest = """
        {
          "latest": { "release": "1.21.4", "snapshot": "25w09b" },
          "versions": [
            { "id": "25w09b", "type": "snapshot", "url": "https://piston-meta.mojang.com/1", "time": "2025-02-26T12:00:00+00:00", "releaseTime": "2025-02-26T12:00:00+00:00", "sha1": "aaa", "complianceLevel": 1 },
            { "id": "1.21.4", "type": "release", "url": "https://piston-meta.mojang.com/2", "time": "2024-12-03T10:00:00+00:00", "releaseTime": "2024-12-03T10:00:00+00:00", "sha1": "bbb", "complianceLevel": 1 },
            { "id": "1.8.9", "type": "release", "url": "https://piston-meta.mojang.com/3", "time": "2015-12-09T10:00:00+00:00", "releaseTime": "2015-12-09T10:00:00+00:00", "sha1": "ccc", "complianceLevel": 0 },
            { "id": "b1.7.3", "type": "old_beta", "url": "https://piston-meta.mojang.com/4", "time": "2011-07-08T10:00:00+00:00", "releaseTime": "2011-07-08T10:00:00+00:00", "sha1": "ddd", "complianceLevel": 0 }
          ]
        }
        """.trimIndent()

        val parsed = json.decodeFromString(VersionManifestV2.serializer(), sampleManifest)
        assertEquals("1.21.4", parsed.latest.release)
        assertEquals(4, parsed.versions.size)

        val latestMatch = parsed.versions.filter {
            VersionCenterRepository.matchesCategory(it, VersionCategory.LATEST_RELEASE, parsed.latest.release, emptySet())
        }
        assertEquals(listOf("1.21.4"), latestMatch.map { it.id })

        val oldBetaMatch = parsed.versions.filter {
            VersionCenterRepository.matchesCategory(it, VersionCategory.OLD_RELEASES, parsed.latest.release, emptySet())
        }
        assertEquals(listOf("b1.7.3"), oldBetaMatch.map { it.id })

        assertEquals(25, VersionCenterRepository.inferRequiredJavaMajor("26.3", null))
        assertEquals(21, VersionCenterRepository.inferRequiredJavaMajor("1.21.4", null))
        assertEquals(17, VersionCenterRepository.inferRequiredJavaMajor("1.19.4", null))
        assertEquals(8, VersionCenterRepository.inferRequiredJavaMajor("1.16.5", null))
        assertEquals(8, VersionCenterRepository.inferRequiredJavaMajor("1.8.9", null))
    }

    @Test
    fun mojangRuleEvaluatorAndMavenPathWork() {
        assertEquals(
            "com/mojang/brigadier/1.2.9/brigadier-1.2.9.jar",
            MojangRuleEvaluator.mavenCoordinateToPath("com.mojang:brigadier:1.2.9"),
        )
        val winOnly = listOf(RuleEntry(action = "allow", os = OsRule(name = "windows")))
        assertFalse(MojangRuleEvaluator.isAllowed(winOnly))

        val linuxAllow = listOf(RuleEntry(action = "allow", os = OsRule(name = "linux")))
        assertTrue(MojangRuleEvaluator.isAllowed(linuxAllow))
    }

    @Test
    fun downloadIntegrityDetectsCorruptionViaSha1() {
        val temp = File.createTempFile("noxer_sha_test", ".jar")
        temp.writeText("real-minecraft-byte-payload")
        val digest = MessageDigest.getInstance("SHA-1").digest(temp.readBytes())
            .joinToString("") { "%02x".format(it) }

        assertTrue(DownloadEngine.verifyFileSha1AndSize(temp, digest, temp.length()))
        assertFalse(DownloadEngine.verifyFileSha1AndSize(temp, "0000deadbeef", temp.length()))
        temp.delete()
    }

    @Test
    fun multipleIndependentInstancesOfSameVersionCoexistInRoom() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NoxerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val paths = NoxerPaths(context)

        val instA = InstallationEntity(
            id = "inst_a",
            name = "1.8.9 PvP",
            minecraftVersionId = "1.8.9",
            loaderType = LoaderType.VANILLA.name,
            gameDirPath = paths.instanceGameDir("189_pvp").absolutePath,
        )
        val instB = InstallationEntity(
            id = "inst_b",
            name = "1.8.9 Skyblock",
            minecraftVersionId = "1.8.9",
            loaderType = LoaderType.FORGE.name,
            gameDirPath = paths.instanceGameDir("189_skyblock").absolutePath,
        )

        db.installationDao().upsert(instA)
        db.installationDao().upsert(instB)

        val all = db.installationDao().observeAll().first()
        assertEquals(2, all.size)
        assertNotEquals(instA.gameDirPath, instB.gameDirPath)
        db.close()
    }

    @Test
    fun crashClassificationAndAccountCryptoWork() {
        val (cat1, _, _) = CrashHandler.classifyFailure(1, "java.lang.OutOfMemoryError: Java heap space")
        assertEquals(LaunchErrorCategory.MEMORY_ERROR, cat1)

        val (cat2, _, _) = CrashHandler.classifyFailure(-102, "dlopen failed: library \"ld-linux-aarch64.so.1\" not found")
        assertEquals(LaunchErrorCategory.UNSUPPORTED_ARCHITECTURE, cat2)

        assertEquals(
            "069a79f4-44e9-4726-a5be-fca90e38aaf5",
            MicrosoftAuthManager.formatUuid("069a79f444e94726a5befca90e38aaf5"),
        )

        val encrypted = AccountCrypto.encrypt("secret_token_payload")
        assertEquals("secret_token_payload", AccountCrypto.decrypt(encrypted))

        val authUrl = MicrosoftAuthManager.buildLiveAuthUrl()
        assertTrue(authUrl.startsWith("https://login.live.com/oauth20_authorize.srf"))
        assertTrue(authUrl.contains("client_id=00000000402b5328"))

        val code = MicrosoftAuthManager.extractCodeFromRedirectUri(
            "https://login.live.com/oauth20_desktop.srf?code=M.R3_BAY.testcode123&lc=1033"
        )
        assertEquals("M.R3_BAY.testcode123", code)

        val err = MicrosoftAuthManager.extractErrorFromRedirectUri(
            "https://login.live.com/oauth20_desktop.srf?error=access_denied&error_description=User+cancelled"
        )
        assertEquals("access_denied: User cancelled", err)
    }

    @Test
    fun offlineProfileUsernameValidationWorks() {
        assertFalse(OfflineProfileValidator.validateUsername("", emptyList()).isValid)
        assertFalse(OfflineProfileValidator.validateUsername("ab", emptyList()).isValid)
        assertFalse(OfflineProfileValidator.validateUsername("thisnameiswaytoolong17", emptyList()).isValid)
        assertFalse(OfflineProfileValidator.validateUsername("bad name", emptyList()).isValid)
        assertFalse(OfflineProfileValidator.validateUsername("bad@name", emptyList()).isValid)

        val valid = OfflineProfileValidator.validateUsername("  Noxer_Player1  ", emptyList())
        assertTrue(valid.isValid)
        assertEquals("Noxer_Player1", valid.normalized)
    }

    @Test
    fun offlineProfilesCoexistPersistAndStaySeparatedFromMicrosoftAccounts() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "noxer_acceptance_${System.nanoTime()}.db"
        val db = Room.databaseBuilder(context, NoxerDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        val paths = NoxerPaths(context)
        val logger = ConsoleLogger(db.consoleLogDao(), paths)
        val settingsManager = SettingsManager(context)
        val offlineManager = OfflineProfileManager(db.offlineProfileDao(), logger)
        val authManager = MicrosoftAuthManager(OkHttpClient(), json, db.accountDao(), logger)
        val sessionManager = AccountSessionManager(authManager, offlineManager, settingsManager, logger)

        // Create two offline profiles and check duplicate rejection (case-insensitive)
        val p1 = offlineManager.createProfile(
            username = "BuilderOne",
            displayName = "Creative Builder",
            iconId = OfflineProfileIcon.HEXAGON.id,
            notes = "Singleplayer world",
        ).getOrThrow()
        val p2 = offlineManager.createProfile(
            username = "Redstone_Lab",
            displayName = "Redstone Tester",
            iconId = OfflineProfileIcon.TERMINAL.id,
            notes = "Offline LAN",
        ).getOrThrow()

        assertNotEquals(p1.id, p2.id)
        val dup = offlineManager.createProfile("builderone", "Duplicate", OfflineProfileIcon.SHIELD.id, "")
        assertTrue(dup.isFailure)

        // Update profile retains ID and validates
        val updated = offlineManager.updateProfile(p1.id, "Builder_One", "Builder One", OfflineProfileIcon.STAR.id, "Updated").getOrThrow()
        assertEquals(p1.id, updated.id)
        assertEquals("Builder_One", updated.username)

        // Select offline profile & set separate offline installation
        sessionManager.selectOfflineProfile(p1.id)
        sessionManager.updateSelectedInstallationForCurrentIdentity("offline_inst_1")
        assertTrue(sessionManager.resolveActiveIdentityNow() is ActiveIdentity.Offline)
        assertEquals("offline_inst_1", settingsManager.settings.first().selectedOfflineInstallationId)

        // Store a verified Microsoft account in the separate accounts table
        val msAcc = AccountEntity(
            uuid = "069a79f4-44e9-4726-a5be-fca90e38aaf5",
            username = "VerifiedPlayer",
            encryptedMcAccessToken = AccountCrypto.encrypt("fake_test_jwt_not_used"),
            encryptedMsRefreshToken = AccountCrypto.encrypt("refresh_tok"),
            encryptedXstsHash = AccountCrypto.encrypt("uhs"),
            expiresAtEpochSec = (System.currentTimeMillis() / 1000L) + 3600L,
            ownsMinecraft = true,
            isActive = true,
        )
        db.accountDao().upsert(msAcc)
        sessionManager.selectMicrosoftAccount(msAcc.uuid)
        sessionManager.updateSelectedInstallationForCurrentIdentity("ms_inst_1")

        val activeMs = sessionManager.resolveActiveIdentityNow()
        assertTrue(activeMs is ActiveIdentity.Microsoft)
        assertEquals("VerifiedPlayer", (activeMs as ActiveIdentity.Microsoft).account.username)
        assertEquals("ms_inst_1", settingsManager.settings.first().selectedMsInstallationId)

        // Logging out the Microsoft account must NEVER silently fall back to an offline profile
        sessionManager.logoutMicrosoftAccount(msAcc.uuid)
        assertEquals(ActiveIdentity.None, sessionManager.resolveActiveIdentityNow())

        // Reopen persisted Room DB to verify offline profiles survive restart
        db.close()
        val reopenedDb = Room.databaseBuilder(context, NoxerDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        val persistedProfiles = reopenedDb.offlineProfileDao().getAll()
        assertEquals(2, persistedProfiles.size)
        assertTrue(persistedProfiles.any { it.username == "Builder_One" })
        assertTrue(persistedProfiles.any { it.username == "Redstone_Lab" })

        reopenedDb.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun offlineLaunchArgumentsUseSupportedLegacyOfflineFormat() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NoxerDatabase::class.java).allowMainThreadQueries().build()
        val paths = NoxerPaths(context)
        val logger = ConsoleLogger(db.consoleLogDao(), paths)
        val okHttp = OkHttpClient()
        val settingsManager = SettingsManager(context)
        val offlineManager = OfflineProfileManager(db.offlineProfileDao(), logger)
        val authManager = MicrosoftAuthManager(okHttp, json, db.accountDao(), logger)
        val sessionManager = AccountSessionManager(authManager, offlineManager, settingsManager, logger)
        val downloadEngine = DownloadEngine(okHttp, logger)
        val modLoaderManager = ModLoaderManager(okHttp, json, paths, downloadEngine, logger)
        val launcher = GameLauncher(
            context = context,
            paths = paths,
            installationDao = db.installationDao(),
            runtimeDao = db.runtimeDao(),
            authManager = authManager,
            offlineProfileManager = offlineManager,
            accountSessionManager = sessionManager,
            downloadEngine = downloadEngine,
            modLoaderManager = modLoaderManager,
            rendererManager = RendererManager(context),
            crashHandler = CrashHandler(db.crashReportDao(), paths),
            consoleLogger = logger,
            json = json,
        )

        val details = VersionDetails(
            id = "1.20.1",
            type = "release",
            mainClass = "net.minecraft.client.main.Main",
            minecraftArguments = "--username \${auth_player_name} --version \${version_name} --uuid \${auth_uuid} --accessToken \${auth_access_token} --userType \${user_type}",
        )

        val offlineArgs = launcher.buildGameArguments(
            details = details,
            username = "LocalHero",
            uuid = "11111111-2222-3333-4444-555555555555",
            accessToken = "0",
            userType = "legacy",
            gameDir = paths.instanceGameDir("test_off"),
            assetsDir = paths.assetsDir,
            width = 1280,
            height = 720,
        )
        assertTrue(offlineArgs.contains("LocalHero"))
        assertTrue(offlineArgs.contains("11111111222233334444555555555555"))
        assertTrue(offlineArgs.contains("0"))
        assertTrue(offlineArgs.contains("legacy"))
        assertFalse(offlineArgs.contains("msa"))

        val msaArgs = launcher.buildGameArguments(
            details = details,
            username = "RealOwner",
            uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            accessToken = "real_mc_token",
            userType = "msa",
            gameDir = paths.instanceGameDir("test_msa"),
            assetsDir = paths.assetsDir,
            width = 1280,
            height = 720,
        )
        assertTrue(msaArgs.contains("msa"))
        assertTrue(msaArgs.contains("real_mc_token"))
        db.close()
    }
}
