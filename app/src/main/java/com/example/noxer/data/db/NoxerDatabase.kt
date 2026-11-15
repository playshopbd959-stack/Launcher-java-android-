package com.example.noxer.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface InstallationDao {
    @Query("SELECT * FROM installations ORDER BY COALESCE(lastPlayedAt, createdAt) DESC")
    fun observeAll(): Flow<List<InstallationEntity>>

    @Query("SELECT * FROM installations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): InstallationEntity?

    @Query("SELECT * FROM installations WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): InstallationEntity?

    @Query("SELECT DISTINCT minecraftVersionId FROM installations WHERE status = 'READY'")
    fun observeInstalledVersionIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: InstallationEntity)

    @Update
    suspend fun update(entity: InstallationEntity)

    @Query("UPDATE installations SET javaRuntimeId = 'auto' WHERE javaRuntimeId = :runtimeId")
    suspend fun clearRuntimeReference(runtimeId: String)

    @Query("DELETE FROM installations WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY isActive DESC, lastAuthenticatedAt DESC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Query("UPDATE accounts SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE accounts SET isActive = 1 WHERE uuid = :uuid")
    suspend fun setActive(uuid: String)

    @Transaction
    suspend fun switchActiveAccount(uuid: String) {
        clearActive()
        setActive(uuid)
    }

    @Query("DELETE FROM accounts WHERE uuid = :uuid")
    suspend fun deleteByUuid(uuid: String)
}

@Dao
interface OfflineProfileDao {
    @Query("SELECT * FROM offline_profiles ORDER BY lastUsedAt DESC, createdAt DESC")
    fun observeAll(): Flow<List<OfflineProfileEntity>>

    @Query("SELECT * FROM offline_profiles ORDER BY lastUsedAt DESC, createdAt DESC")
    suspend fun getAll(): List<OfflineProfileEntity>

    @Query("SELECT * FROM offline_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): OfflineProfileEntity?

    @Query("SELECT * FROM offline_profiles WHERE LOWER(username) = LOWER(:username) LIMIT 1")
    suspend fun getByUsernameIgnoreCase(username: String): OfflineProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: OfflineProfileEntity)

    @Update
    suspend fun update(profile: OfflineProfileEntity)

    @Query("UPDATE offline_profiles SET lastUsedAt = :timestamp WHERE id = :id")
    suspend fun updateLastUsed(id: String, timestamp: Long)

    @Query("UPDATE offline_profiles SET preferredInstallationId = :installationId WHERE id = :id")
    suspend fun updatePreferredInstallation(id: String, installationId: String?)

    @Query("DELETE FROM offline_profiles WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface RuntimeDao {
    @Query("SELECT * FROM runtimes ORDER BY isDefault DESC, majorVersion DESC, installedAt DESC")
    fun observeAll(): Flow<List<RuntimeEntity>>

    @Query("SELECT * FROM runtimes ORDER BY isDefault DESC, majorVersion DESC, installedAt DESC")
    suspend fun getAll(): List<RuntimeEntity>

    @Query("SELECT * FROM runtimes WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RuntimeEntity?

    @Query("SELECT * FROM runtimes WHERE majorVersion = :majorVersion AND isInstalled = 1 AND isVerified = 1 ORDER BY isDefault DESC, installedAt DESC LIMIT 1")
    suspend fun getVerifiedByMajorVersion(majorVersion: Int): RuntimeEntity?

    @Query("SELECT * FROM runtimes WHERE majorVersion = :majorVersion AND isInstalled = 1 ORDER BY isVerified DESC, isDefault DESC, installedAt DESC LIMIT 1")
    suspend fun getInstalledByMajorVersion(majorVersion: Int): RuntimeEntity?

    @Query("SELECT * FROM runtimes WHERE isDefault = 1 AND isInstalled = 1 LIMIT 1")
    suspend fun getDefaultRuntime(): RuntimeEntity?

    @Query("UPDATE runtimes SET isDefault = 0")
    suspend fun clearDefaultFlags()

    @Query("UPDATE runtimes SET isDefault = 1 WHERE id = :id")
    suspend fun markDefault(id: String)

    @Transaction
    suspend fun setDefaultRuntime(id: String) {
        clearDefaultFlags()
        markDefault(id)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(runtime: RuntimeEntity)

    @Query("DELETE FROM runtimes WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ControlProfileDao {
    @Query("SELECT * FROM control_profiles ORDER BY isDefault DESC, name ASC")
    fun observeAll(): Flow<List<ControlProfileEntity>>

    @Query("SELECT * FROM control_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ControlProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ControlProfileEntity)

    @Query("DELETE FROM control_profiles WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface CachedVersionDao {
    @Query("SELECT * FROM cached_versions ORDER BY manifestIndex ASC")
    fun observeAll(): Flow<List<CachedVersionEntity>>

    @Query("SELECT COUNT(*) FROM cached_versions")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(versions: List<CachedVersionEntity>)

    @Query("DELETE FROM cached_versions")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(versions: List<CachedVersionEntity>) {
        clearAll()
        insertAll(versions)
    }
}

@Dao
interface ConsoleLogDao {
    @Query("SELECT * FROM console_logs ORDER BY id DESC LIMIT 600")
    fun observeRecent(): Flow<List<ConsoleLogEntity>>

    @Insert
    suspend fun insert(entry: ConsoleLogEntity)

    @Query("DELETE FROM console_logs")
    suspend fun clearAll()
}

@Dao
interface CrashReportDao {
    @Query("SELECT * FROM crash_reports ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<CrashReportEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: CrashReportEntity)

    @Query("DELETE FROM crash_reports WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Database(
    entities = [
        InstallationEntity::class,
        AccountEntity::class,
        OfflineProfileEntity::class,
        RuntimeEntity::class,
        ControlProfileEntity::class,
        CachedVersionEntity::class,
        ConsoleLogEntity::class,
        CrashReportEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class NoxerDatabase : RoomDatabase() {
    abstract fun installationDao(): InstallationDao
    abstract fun accountDao(): AccountDao
    abstract fun offlineProfileDao(): OfflineProfileDao
    abstract fun runtimeDao(): RuntimeDao
    abstract fun controlProfileDao(): ControlProfileDao
    abstract fun cachedVersionDao(): CachedVersionDao
    abstract fun consoleLogDao(): ConsoleLogDao
    abstract fun crashReportDao(): CrashReportDao
}
