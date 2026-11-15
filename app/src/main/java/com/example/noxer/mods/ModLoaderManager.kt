package com.example.noxer.mods

import com.example.noxer.console.ConsoleLogger
import com.example.noxer.core.NoxerPaths
import com.example.noxer.data.db.LoaderType
import com.example.noxer.download.DownloadCategory
import com.example.noxer.download.DownloadEngine
import com.example.noxer.download.DownloadTask
import com.example.noxer.download.MojangRuleEvaluator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

@Serializable
data class FabricLoaderEntry(
    val loader: FabricLoaderMeta,
    val intermediary: FabricIntermediaryMeta? = null,
)

@Serializable
data class FabricLoaderMeta(
    val version: String,
    val stable: Boolean = true,
)

@Serializable
data class FabricIntermediaryMeta(
    val version: String,
)

@Serializable
data class FabricProfileJson(
    val id: String,
    val inheritsFrom: String? = null,
    val mainClass: String,
    val libraries: List<FabricLibrary> = emptyList(),
)

@Serializable
data class FabricLibrary(
    val name: String,
    val url: String = "https://maven.fabricmc.net/",
    val sha1: String? = null,
    val size: Long = 0L,
)

@Serializable
private data class FabricModMetadata(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val description: String = "",
)

@Serializable
private data class ForgePromotionsResponse(
    val promos: Map<String, String> = emptyMap(),
)

@Serializable
private data class MrpackIndex(
    val name: String = "Modpack",
    val versionId: String = "",
    val dependencies: Map<String, String> = emptyMap(),
    val files: List<MrpackFile> = emptyList(),
)

@Serializable
private data class MrpackFile(
    val path: String,
    val hashes: Map<String, String> = emptyMap(),
    val downloads: List<String> = emptyList(),
    val fileSize: Long = 0L,
)

data class InstalledModInfo(
    val file: File,
    val fileName: String,
    val modId: String,
    val displayName: String,
    val version: String,
    val detectedLoader: LoaderType?,
    val isEnabled: Boolean,
    val sizeBytes: Long,
    val conflictWarning: String? = null,
)

data class LoaderVersionOption(
    val loaderType: LoaderType,
    val version: String,
    val isRecommended: Boolean = false,
)

class ModLoaderManager(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val paths: NoxerPaths,
    private val downloadEngine: DownloadEngine,
    private val consoleLogger: ConsoleLogger,
) {
    suspend fun fetchCompatibleLoaders(mcVersion: String): Map<LoaderType, List<LoaderVersionOption>> =
        withContext(Dispatchers.IO) {
            val result = mutableMapOf<LoaderType, List<LoaderVersionOption>>()
            result[LoaderType.VANILLA] = listOf(LoaderVersionOption(LoaderType.VANILLA, mcVersion, true))

            // 1. Fabric Meta API
            runCatching {
                val raw = executeGet("https://meta.fabricmc.net/v2/versions/loader/$mcVersion")
                val entries = json.decodeFromString<List<FabricLoaderEntry>>(raw)
                if (entries.isNotEmpty()) {
                    result[LoaderType.FABRIC] = entries.take(15).mapIndexed { idx, e ->
                        LoaderVersionOption(
                            loaderType = LoaderType.FABRIC,
                            version = e.loader.version,
                            isRecommended = e.loader.stable && idx == 0,
                        )
                    }
                }
            }

            // 2. NeoForge Maven Metadata XML
            runCatching {
                val xml = executeGet("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")
                val allVersions = Regex("<version>([^<]+)</version>")
                    .findAll(xml)
                    .map { it.groupValues[1] }
                    .toList()
                val mcParts = mcVersion.split(".")
                if (mcParts.size >= 2 && mcParts[0] == "1") {
                    val minor = mcParts[1]
                    val patch = mcParts.getOrNull(2) ?: "0"
                    val prefix = "$minor.$patch."
                    val matching = allVersions.filter { it.startsWith(prefix) }.reversed().take(12)
                    if (matching.isNotEmpty()) {
                        result[LoaderType.NEOFORGE] = matching.mapIndexed { idx, v ->
                            LoaderVersionOption(LoaderType.NEOFORGE, v, idx == 0)
                        }
                    }
                }
            }

            // 3. Forge Promotions API
            runCatching {
                val raw = executeGet("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json")
                val promos = json.decodeFromString(ForgePromotionsResponse.serializer(), raw)
                val options = mutableListOf<LoaderVersionOption>()
                promos.promos["$mcVersion-recommended"]?.let {
                    options += LoaderVersionOption(LoaderType.FORGE, it, isRecommended = true)
                }
                promos.promos["$mcVersion-latest"]?.let { latest ->
                    if (options.none { it.version == latest }) {
                        options += LoaderVersionOption(LoaderType.FORGE, latest, isRecommended = options.isEmpty())
                    }
                }
                if (options.isNotEmpty()) {
                    result[LoaderType.FORGE] = options
                }
            }

            result
        }

    suspend fun installFabricProfile(
        instanceId: String,
        mcVersion: String,
        loaderVersion: String,
    ): Result<FabricProfileJson> = withContext(Dispatchers.IO) {
        try {
            val url = "https://meta.fabricmc.net/v2/versions/loader/$mcVersion/$loaderVersion/profile/json"
            val raw = executeGet(url)
            val profile = json.decodeFromString(FabricProfileJson.serializer(), raw)

            val profileFile = File(paths.instanceGameDir(instanceId), "fabric-profile.json")
            profileFile.writeText(raw)

            val tasks = profile.libraries.map { lib ->
                val relPath = MojangRuleEvaluator.mavenCoordinateToPath(lib.name)
                val base = if (lib.url.endsWith("/")) lib.url else "${lib.url}/"
                DownloadTask(
                    id = "fabric-lib:${lib.name}",
                    label = lib.name,
                    url = "$base$relPath",
                    destination = paths.libraryFile(relPath),
                    expectedSha1 = lib.sha1,
                    expectedSize = lib.size,
                    category = DownloadCategory.MOD_LOADER,
                )
            }
            val dlResult = downloadEngine.downloadAll("Installing Fabric $loaderVersion", tasks)
            if (dlResult.isFailure) return@withContext Result.failure(dlResult.exceptionOrNull()!!)
            consoleLogger.info("ModLoader", "Installed Fabric $loaderVersion for MC $mcVersion (${profile.libraries.size} libraries)")
            Result.success(profile)
        } catch (t: Throwable) {
            consoleLogger.error("ModLoader", "Fabric installation failed: ${t.message}")
            Result.failure(t)
        }
    }

    suspend fun scanInstanceMods(
        instanceId: String,
        instanceLoader: LoaderType,
    ): List<InstalledModInfo> = withContext(Dispatchers.IO) {
        val modsDir = File(paths.instanceGameDir(instanceId), "mods")
        if (!modsDir.exists()) return@withContext emptyList()
        val files = modsDir.listFiles { f ->
            f.isFile && (f.name.endsWith(".jar", ignoreCase = true) || f.name.endsWith(".jar.disabled", ignoreCase = true))
        }?.sortedBy { it.name.lowercase() }.orEmpty()

        val rawList = files.map { file -> parseModJar(file) }
        val idCounts = rawList.filter { it.isEnabled && it.modId.isNotBlank() }.groupingBy { it.modId }.eachCount()

        rawList.map { mod ->
            val conflict = when {
                mod.isEnabled && (idCounts[mod.modId] ?: 0) > 1 ->
                    "Duplicate mod ID '${mod.modId}' enabled multiple times."
                mod.isEnabled && instanceLoader == LoaderType.VANILLA ->
                    "Instance uses Vanilla; mods require Fabric, Forge, or NeoForge."
                mod.isEnabled && mod.detectedLoader != null && mod.detectedLoader != instanceLoader ->
                    "Mod targets ${mod.detectedLoader.displayName}, but instance uses ${instanceLoader.displayName}."
                else -> null
            }
            mod.copy(conflictWarning = conflict)
        }
    }

    suspend fun toggleModEnabled(mod: InstalledModInfo): Boolean = withContext(Dispatchers.IO) {
        val target = if (mod.isEnabled) {
            File(mod.file.parentFile, "${mod.file.name}.disabled")
        } else {
            File(mod.file.parentFile, mod.file.name.removeSuffix(".disabled"))
        }
        mod.file.renameTo(target)
    }

    suspend fun deleteMod(mod: InstalledModInfo): Boolean = withContext(Dispatchers.IO) {
        mod.file.delete()
    }

    suspend fun importMrpackFile(instanceId: String, localZipFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val gameDir = paths.instanceGameDir(instanceId)
            val index: MrpackIndex
            ZipFile(localZipFile).use { zip ->
                val idxEntry = zip.getEntry("modrinth.index.json")
                    ?: return@withContext Result.failure(IOException("Not a valid .mrpack archive (missing modrinth.index.json)"))
                val idxStr = zip.getInputStream(idxEntry).bufferedReader().readText()
                index = json.decodeFromString(MrpackIndex.serializer(), idxStr)

                // Extract overrides safely
                val canonicalGameDir = gameDir.canonicalPath
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("overrides/") && !entry.isDirectory) {
                        val rel = entry.name.removePrefix("overrides/")
                        val outFile = File(gameDir, rel)
                        if (!outFile.canonicalPath.startsWith(canonicalGameDir)) {
                            throw SecurityException("Blocked unsafe mrpack override path: ${entry.name}")
                        }
                        outFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(outFile).use { output -> input.copyTo(output) }
                        }
                    }
                }
            }

            val canonicalGameDir = gameDir.canonicalPath
            val tasks = index.files.mapNotNull { mf ->
                val url = mf.downloads.firstOrNull() ?: return@mapNotNull null
                val dest = File(gameDir, mf.path)
                if (!dest.canonicalPath.startsWith(canonicalGameDir)) return@mapNotNull null
                DownloadTask(
                    id = "mrpack:${mf.path}",
                    label = File(mf.path).name,
                    url = url,
                    destination = dest,
                    expectedSha1 = mf.hashes["sha1"],
                    expectedSize = mf.fileSize,
                    category = DownloadCategory.MOD_LOADER,
                )
            }
            val dl = downloadEngine.downloadAll("Importing ${index.name}", tasks)
            if (dl.isFailure) return@withContext Result.failure(dl.exceptionOrNull()!!)
            consoleLogger.info("Modpack", "Imported ${index.name} (${tasks.size} files) into instance $instanceId")
            Result.success("${index.name} (${tasks.size} modpack files)")
        } catch (t: Throwable) {
            consoleLogger.error("Modpack", "Failed .mrpack import: ${t.message}")
            Result.failure(t)
        }
    }

    private fun parseModJar(file: File): InstalledModInfo {
        val enabled = !file.name.endsWith(".disabled", ignoreCase = true)
        var modId = file.name.removeSuffix(".disabled").removeSuffix(".jar")
        var displayName = modId
        var version = "unknown"
        var detectedLoader: LoaderType? = null

        runCatching {
            ZipFile(file).use { zip ->
                val fabricEntry = zip.getEntry("fabric.mod.json")
                val neoEntry = zip.getEntry("META-INF/neoforge.mods.toml")
                val forgeEntry = zip.getEntry("META-INF/mods.toml")
                when {
                    fabricEntry != null -> {
                        detectedLoader = LoaderType.FABRIC
                        val text = zip.getInputStream(fabricEntry).bufferedReader().readText()
                        val meta = json.decodeFromString(FabricModMetadata.serializer(), text)
                        if (meta.id.isNotBlank()) modId = meta.id
                        if (meta.name.isNotBlank()) displayName = meta.name
                        if (meta.version.isNotBlank()) version = meta.version
                    }
                    neoEntry != null -> {
                        detectedLoader = LoaderType.NEOFORGE
                        val text = zip.getInputStream(neoEntry).bufferedReader().readText()
                        Regex("""modId\s*=\s*"([^"]+)"""").find(text)?.let { modId = it.groupValues[1] }
                        Regex("""displayName\s*=\s*"([^"]+)"""").find(text)?.let { displayName = it.groupValues[1] }
                        Regex("""version\s*=\s*"([^"]+)"""").find(text)?.let { version = it.groupValues[1] }
                    }
                    forgeEntry != null -> {
                        detectedLoader = LoaderType.FORGE
                        val text = zip.getInputStream(forgeEntry).bufferedReader().readText()
                        Regex("""modId\s*=\s*"([^"]+)"""").find(text)?.let { modId = it.groupValues[1] }
                        Regex("""displayName\s*=\s*"([^"]+)"""").find(text)?.let { displayName = it.groupValues[1] }
                        Regex("""version\s*=\s*"([^"]+)"""").find(text)?.let { version = it.groupValues[1] }
                    }
                }
            }
        }

        return InstalledModInfo(
            file = file,
            fileName = file.name,
            modId = modId,
            displayName = displayName,
            version = version,
            detectedLoader = detectedLoader,
            isEnabled = enabled,
            sizeBytes = file.length(),
        )
    }

    private fun executeGet(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "NoxerLauncher/1.0 (Android)")
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from $url")
            return resp.body?.string() ?: throw IOException("Empty response from $url")
        }
    }
}
