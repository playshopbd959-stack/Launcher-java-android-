package com.example.noxer.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.noxer.graphics.RendererBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.noxerDataStore by preferencesDataStore(name = "noxer_settings")

data class LauncherSettings(
    val defaultMemoryMb: Int = 2048,
    val defaultJvmArgs: String = "-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20",
    val defaultRendererId: String = RendererBackend.GL4ES_GLES3.id,
    val resolutionScalePercent: Int = 100,
    val fullscreen: Boolean = true,
    val downloadConcurrency: Int = 6,
    val verifySha1OnLaunch: Boolean = true,
    val selectedInstallationId: String? = null,
    val performanceProfile: String = "Balanced", // Battery, Balanced, Performance
    val activeIdentityType: String? = null, // "MICROSOFT" or "OFFLINE"
    val selectedMicrosoftUuid: String? = null,
    val selectedOfflineProfileId: String? = null,
    val selectedMsInstallationId: String? = null,
    val selectedOfflineInstallationId: String? = null,
    val lastUsedProfileType: String? = null,
    val lastUsedProfileId: String? = null,
)

class SettingsManager(private val context: Context) {
    private object Keys {
        val DEFAULT_RAM_MB = intPreferencesKey("default_ram_mb")
        val DEFAULT_JVM_ARGS = stringPreferencesKey("default_jvm_args")
        val DEFAULT_RENDERER = stringPreferencesKey("default_renderer")
        val RES_SCALE = intPreferencesKey("resolution_scale")
        val FULLSCREEN = booleanPreferencesKey("fullscreen")
        val CONCURRENCY = intPreferencesKey("download_concurrency")
        val VERIFY_SHA1 = booleanPreferencesKey("verify_sha1")
        val SELECTED_INSTANCE = stringPreferencesKey("selected_instance_id")
        val PERF_PROFILE = stringPreferencesKey("performance_profile")
        val ACTIVE_IDENTITY_TYPE = stringPreferencesKey("active_identity_type")
        val SELECTED_MS_UUID = stringPreferencesKey("selected_ms_uuid")
        val SELECTED_OFFLINE_ID = stringPreferencesKey("selected_offline_profile_id")
        val SELECTED_MS_INSTANCE = stringPreferencesKey("selected_ms_instance_id")
        val SELECTED_OFFLINE_INSTANCE = stringPreferencesKey("selected_offline_instance_id")
        val LAST_USED_PROFILE_TYPE = stringPreferencesKey("last_used_profile_type")
        val LAST_USED_PROFILE_ID = stringPreferencesKey("last_used_profile_id")
    }

    private fun mapPrefs(prefs: androidx.datastore.preferences.core.Preferences): LauncherSettings {
        val identityType = prefs[Keys.ACTIVE_IDENTITY_TYPE]
        val msInst = prefs[Keys.SELECTED_MS_INSTANCE]
        val offInst = prefs[Keys.SELECTED_OFFLINE_INSTANCE]
        val activeInst = when (identityType) {
            "OFFLINE" -> offInst ?: prefs[Keys.SELECTED_INSTANCE]
            "MICROSOFT" -> msInst ?: prefs[Keys.SELECTED_INSTANCE]
            else -> prefs[Keys.SELECTED_INSTANCE]
        }
        return LauncherSettings(
            defaultMemoryMb = prefs[Keys.DEFAULT_RAM_MB] ?: 2048,
            defaultJvmArgs = prefs[Keys.DEFAULT_JVM_ARGS]
                ?: "-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20",
            defaultRendererId = prefs[Keys.DEFAULT_RENDERER] ?: RendererBackend.GL4ES_GLES3.id,
            resolutionScalePercent = prefs[Keys.RES_SCALE] ?: 100,
            fullscreen = prefs[Keys.FULLSCREEN] ?: true,
            downloadConcurrency = prefs[Keys.CONCURRENCY] ?: 6,
            verifySha1OnLaunch = prefs[Keys.VERIFY_SHA1] ?: true,
            selectedInstallationId = activeInst,
            performanceProfile = prefs[Keys.PERF_PROFILE] ?: "Balanced",
            activeIdentityType = identityType,
            selectedMicrosoftUuid = prefs[Keys.SELECTED_MS_UUID],
            selectedOfflineProfileId = prefs[Keys.SELECTED_OFFLINE_ID],
            selectedMsInstallationId = msInst,
            selectedOfflineInstallationId = offInst,
            lastUsedProfileType = prefs[Keys.LAST_USED_PROFILE_TYPE],
            lastUsedProfileId = prefs[Keys.LAST_USED_PROFILE_ID],
        )
    }

    val settings: Flow<LauncherSettings> = context.noxerDataStore.data.map(::mapPrefs)

    suspend fun updateSelectedInstallation(id: String?) {
        context.noxerDataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.SELECTED_INSTANCE) else prefs[Keys.SELECTED_INSTANCE] = id
            when (prefs[Keys.ACTIVE_IDENTITY_TYPE]) {
                "OFFLINE" -> {
                    if (id == null) prefs.remove(Keys.SELECTED_OFFLINE_INSTANCE) else prefs[Keys.SELECTED_OFFLINE_INSTANCE] = id
                }
                "MICROSOFT" -> {
                    if (id == null) prefs.remove(Keys.SELECTED_MS_INSTANCE) else prefs[Keys.SELECTED_MS_INSTANCE] = id
                }
            }
        }
    }

    suspend fun updateSelectedInstallationForCategory(category: String, id: String?) {
        context.noxerDataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.SELECTED_INSTANCE) else prefs[Keys.SELECTED_INSTANCE] = id
            if (category == "OFFLINE") {
                if (id == null) prefs.remove(Keys.SELECTED_OFFLINE_INSTANCE) else prefs[Keys.SELECTED_OFFLINE_INSTANCE] = id
            } else {
                if (id == null) prefs.remove(Keys.SELECTED_MS_INSTANCE) else prefs[Keys.SELECTED_MS_INSTANCE] = id
            }
        }
    }

    suspend fun setActiveMicrosoftIdentity(uuid: String) {
        context.noxerDataStore.edit { prefs ->
            prefs[Keys.ACTIVE_IDENTITY_TYPE] = "MICROSOFT"
            prefs[Keys.SELECTED_MS_UUID] = uuid
            prefs[Keys.LAST_USED_PROFILE_TYPE] = "MICROSOFT"
            prefs[Keys.LAST_USED_PROFILE_ID] = uuid
            val msInst = prefs[Keys.SELECTED_MS_INSTANCE]
            if (msInst != null) {
                prefs[Keys.SELECTED_INSTANCE] = msInst
            }
        }
    }

    suspend fun setActiveOfflineIdentity(profileId: String, profilePreferredInstallationId: String? = null) {
        context.noxerDataStore.edit { prefs ->
            prefs[Keys.ACTIVE_IDENTITY_TYPE] = "OFFLINE"
            prefs[Keys.SELECTED_OFFLINE_ID] = profileId
            prefs[Keys.LAST_USED_PROFILE_TYPE] = "OFFLINE"
            prefs[Keys.LAST_USED_PROFILE_ID] = profileId
            val offInst = profilePreferredInstallationId ?: prefs[Keys.SELECTED_OFFLINE_INSTANCE]
            if (offInst != null) {
                prefs[Keys.SELECTED_OFFLINE_INSTANCE] = offInst
                prefs[Keys.SELECTED_INSTANCE] = offInst
            }
        }
    }

    suspend fun clearActiveIdentity() {
        context.noxerDataStore.edit { prefs ->
            prefs.remove(Keys.ACTIVE_IDENTITY_TYPE)
        }
    }

    suspend fun updateSettings(transform: (LauncherSettings) -> LauncherSettings) {
        context.noxerDataStore.edit { prefs ->
            val current = mapPrefs(prefs)
            val next = transform(current)
            prefs[Keys.DEFAULT_RAM_MB] = next.defaultMemoryMb.coerceIn(512, 8192)
            prefs[Keys.DEFAULT_JVM_ARGS] = next.defaultJvmArgs
            prefs[Keys.DEFAULT_RENDERER] = next.defaultRendererId
            prefs[Keys.RES_SCALE] = next.resolutionScalePercent.coerceIn(50, 100)
            prefs[Keys.FULLSCREEN] = next.fullscreen
            prefs[Keys.CONCURRENCY] = next.downloadConcurrency.coerceIn(1, 16)
            prefs[Keys.VERIFY_SHA1] = next.verifySha1OnLaunch
            prefs[Keys.PERF_PROFILE] = next.performanceProfile
        }
    }
}
