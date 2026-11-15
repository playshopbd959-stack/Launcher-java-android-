package com.example.noxer.auth

import com.example.noxer.data.db.AccountEntity
import com.example.noxer.data.db.OfflineProfileEntity
import com.example.noxer.data.db.OfflineProfileDao
import com.example.noxer.console.ConsoleLogger
import com.example.noxer.settings.SettingsManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.UUID

enum class OfflineProfileIcon(val id: String, val label: String) {
    SHIELD("SHIELD", "Guardian"),
    BOLT("BOLT", "Striker"),
    COMPASS("COMPASS", "Explorer"),
    TERMINAL("TERMINAL", "Engineer"),
    HEXAGON("HEXAGON", "Builder"),
    FLAME("FLAME", "Blaze"),
    STAR("STAR", "Pioneer"),
    GAMEPAD("GAMEPAD", "Arcade"),
    ;

    companion object {
        fun fromId(id: String?): OfflineProfileIcon =
            entries.firstOrNull { it.id == id } ?: SHIELD
    }
}

enum class IdentityCategory {
    MICROSOFT,
    OFFLINE,
}

sealed interface ActiveIdentity {
    data class Microsoft(val account: AccountEntity) : ActiveIdentity
    data class Offline(val profile: OfflineProfileEntity) : ActiveIdentity
    data object None : ActiveIdentity
}

data class UsernameValidationResult(
    val isValid: Boolean,
    val normalized: String,
    val error: String? = null,
)

object OfflineProfileValidator {
    private val MINECRAFT_USERNAME_REGEX = Regex("^[A-Za-z0-9_]{3,16}$")

    fun validateUsername(
        raw: String,
        existingProfiles: List<OfflineProfileEntity>,
        editingProfileId: String? = null,
    ): UsernameValidationResult {
        val candidate = raw.trim()
        if (candidate.isEmpty()) {
            return UsernameValidationResult(false, candidate, "Player username cannot be empty.")
        }
        if (candidate.length < 3) {
            return UsernameValidationResult(false, candidate, "Username must be at least 3 characters.")
        }
        if (candidate.length > 16) {
            return UsernameValidationResult(false, candidate, "Username cannot exceed 16 characters.")
        }
        if (!MINECRAFT_USERNAME_REGEX.matches(candidate)) {
            return UsernameValidationResult(
                false,
                candidate,
                "Use only letters (A-Z, a-z), numbers (0-9), and underscores (_). Spaces and symbols are not allowed.",
            )
        }
        val duplicate = existingProfiles.firstOrNull {
            it.id != editingProfileId && it.username.equals(candidate, ignoreCase = true)
        }
        if (duplicate != null) {
            return UsernameValidationResult(
                false,
                candidate,
                "A local offline profile named '${duplicate.username}' already exists.",
            )
        }
        return UsernameValidationResult(true, candidate, null)
    }

    fun normalizeDisplayName(rawDisplayName: String, validUsername: String): String {
        val trimmed = rawDisplayName.trim()
        return if (trimmed.isBlank()) validUsername else trimmed.take(32)
    }
}

class OfflineProfileManager(
    private val offlineProfileDao: OfflineProfileDao,
    private val consoleLogger: ConsoleLogger,
) {
    val profiles: Flow<List<OfflineProfileEntity>> = offlineProfileDao.observeAll()

    fun generateLocalProfileUuid(): String = UUID.randomUUID().toString()

    suspend fun getProfileById(id: String): OfflineProfileEntity? = offlineProfileDao.getById(id)

    suspend fun createProfile(
        username: String,
        displayName: String,
        iconId: String,
        notes: String,
        preferredInstallationId: String? = null,
    ): Result<OfflineProfileEntity> {
        val existing = offlineProfileDao.getAll()
        val validation = OfflineProfileValidator.validateUsername(username, existing)
        if (!validation.isValid) {
            return Result.failure(IllegalArgumentException(validation.error ?: "Invalid username"))
        }
        val now = System.currentTimeMillis()
        val entity = OfflineProfileEntity(
            id = generateLocalProfileUuid(),
            username = validation.normalized,
            displayName = OfflineProfileValidator.normalizeDisplayName(displayName, validation.normalized),
            iconId = OfflineProfileIcon.fromId(iconId).id,
            notes = notes.trim().take(240),
            createdAt = now,
            lastUsedAt = now,
            preferredInstallationId = preferredInstallationId,
        )
        offlineProfileDao.upsert(entity)
        consoleLogger.info("OfflineAuth", "Created UNLICENSED / OFFLINE PROFILE '${entity.username}' (local uuid=${entity.id}).")
        return Result.success(entity)
    }

    suspend fun updateProfile(
        profileId: String,
        username: String,
        displayName: String,
        iconId: String,
        notes: String,
    ): Result<OfflineProfileEntity> {
        val current = offlineProfileDao.getById(profileId)
            ?: return Result.failure(IOException("Offline profile no longer exists."))
        val existing = offlineProfileDao.getAll()
        val validation = OfflineProfileValidator.validateUsername(username, existing, editingProfileId = profileId)
        if (!validation.isValid) {
            return Result.failure(IllegalArgumentException(validation.error ?: "Invalid username"))
        }
        val updated = current.copy(
            username = validation.normalized,
            displayName = OfflineProfileValidator.normalizeDisplayName(displayName, validation.normalized),
            iconId = OfflineProfileIcon.fromId(iconId).id,
            notes = notes.trim().take(240),
        )
        offlineProfileDao.update(updated)
        consoleLogger.info("OfflineAuth", "Updated local offline profile '${updated.username}' (${updated.id}).")
        return Result.success(updated)
    }

    suspend fun deleteProfile(profileId: String) {
        val existing = offlineProfileDao.getById(profileId)
        offlineProfileDao.deleteById(profileId)
        if (existing != null) {
            consoleLogger.info("OfflineAuth", "Deleted local offline profile '${existing.username}' (${existing.id}).")
        }
    }

    suspend fun markUsed(profileId: String) {
        offlineProfileDao.updateLastUsed(profileId, System.currentTimeMillis())
    }

    suspend fun setPreferredInstallation(profileId: String, installationId: String?) {
        offlineProfileDao.updatePreferredInstallation(profileId, installationId)
    }
}

/**
 * Coordinates strict separation between verified Microsoft accounts and local offline profiles.
 * Never falls back from a selected Microsoft account to an offline profile (or vice versa).
 */
class AccountSessionManager(
    private val authManager: MicrosoftAuthManager,
    private val offlineProfileManager: OfflineProfileManager,
    private val settingsManager: SettingsManager,
    private val consoleLogger: ConsoleLogger,
) {
    val activeIdentity: Flow<ActiveIdentity> = combine(
        settingsManager.settings,
        authManager.accounts,
        offlineProfileManager.profiles,
    ) { settings, msAccounts, offlineProfiles ->
        when (settings.activeIdentityType) {
            IdentityCategory.MICROSOFT.name -> {
                val targetUuid = settings.selectedMicrosoftUuid
                val match = msAccounts.firstOrNull {
                    (targetUuid != null && it.uuid == targetUuid) || (targetUuid == null && it.isActive)
                }
                if (match != null) ActiveIdentity.Microsoft(match) else ActiveIdentity.None
            }
            IdentityCategory.OFFLINE.name -> {
                val targetId = settings.selectedOfflineProfileId
                val match = offlineProfiles.firstOrNull { it.id == targetId }
                if (match != null) ActiveIdentity.Offline(match) else ActiveIdentity.None
            }
            else -> {
                // Backward compatibility: only auto-bind if a verified Microsoft account is already marked active
                val legacyMs = msAccounts.firstOrNull { it.isActive }
                if (legacyMs != null) ActiveIdentity.Microsoft(legacyMs) else ActiveIdentity.None
            }
        }
    }

    suspend fun resolveActiveIdentityNow(): ActiveIdentity = activeIdentity.first()

    suspend fun selectMicrosoftAccount(uuid: String) {
        authManager.switchAccount(uuid)
        settingsManager.setActiveMicrosoftIdentity(uuid)
        consoleLogger.info("Accounts", "Switched active identity to Microsoft account ($uuid).")
    }

    suspend fun onMicrosoftAuthenticated(account: AccountEntity) {
        settingsManager.setActiveMicrosoftIdentity(account.uuid)
    }

    suspend fun selectOfflineProfile(profileId: String) {
        val profile = offlineProfileManager.getProfileById(profileId) ?: return
        offlineProfileManager.markUsed(profileId)
        settingsManager.setActiveOfflineIdentity(profileId, profile.preferredInstallationId)
        consoleLogger.info("Accounts", "Switched active identity to UNLICENSED / OFFLINE PROFILE '${profile.username}' (${profile.id}).")
    }

    suspend fun logoutMicrosoftAccount(uuid: String) {
        val current = settingsManager.settings.first()
        authManager.logoutAccount(uuid)
        if (current.activeIdentityType == IdentityCategory.MICROSOFT.name && current.selectedMicrosoftUuid == uuid) {
            settingsManager.clearActiveIdentity()
        }
    }

    suspend fun deleteOfflineProfile(profileId: String) {
        val current = settingsManager.settings.first()
        offlineProfileManager.deleteProfile(profileId)
        if (current.activeIdentityType == IdentityCategory.OFFLINE.name && current.selectedOfflineProfileId == profileId) {
            settingsManager.clearActiveIdentity()
        }
    }

    suspend fun clearActiveSelection() {
        settingsManager.clearActiveIdentity()
    }

    suspend fun updateSelectedInstallationForCurrentIdentity(installationId: String?) {
        val current = settingsManager.settings.first()
        when (current.activeIdentityType) {
            IdentityCategory.OFFLINE.name -> {
                val profileId = current.selectedOfflineProfileId
                if (profileId != null) {
                    offlineProfileManager.setPreferredInstallation(profileId, installationId)
                }
                settingsManager.updateSelectedInstallationForCategory(IdentityCategory.OFFLINE.name, installationId)
            }
            else -> {
                settingsManager.updateSelectedInstallationForCategory(IdentityCategory.MICROSOFT.name, installationId)
            }
        }
    }
}
