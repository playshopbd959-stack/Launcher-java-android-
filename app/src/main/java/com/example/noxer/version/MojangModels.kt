package com.example.noxer.version

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class VersionManifestV2(
    val latest: LatestVersions,
    val versions: List<VersionSummary>,
)

@Serializable
data class LatestVersions(
    val release: String,
    val snapshot: String,
)

@Serializable
data class VersionSummary(
    val id: String,
    val type: String, // "release", "snapshot", "old_beta", "old_alpha"
    val url: String,
    val time: String,
    val releaseTime: String,
    val sha1: String,
    val complianceLevel: Int = 0,
)

@Serializable
data class VersionDetails(
    val id: String,
    val type: String,
    val mainClass: String,
    val time: String? = null,
    val releaseTime: String? = null,
    val assets: String? = null,
    val assetIndex: AssetIndexInfo? = null,
    val downloads: VersionDownloads? = null,
    val javaVersion: JavaVersionInfo? = null,
    val libraries: List<LibraryEntry> = emptyList(),
    val logging: LoggingConfig? = null,
    val minecraftArguments: String? = null,
    val arguments: ArgumentsBlock? = null,
    val complianceLevel: Int = 0,
    val inheritsFrom: String? = null,
)

@Serializable
data class AssetIndexInfo(
    val id: String,
    val sha1: String,
    val size: Long,
    val totalSize: Long = 0,
    val url: String,
)

@Serializable
data class VersionDownloads(
    val client: DownloadArtifact? = null,
    @SerialName("client_mappings") val clientMappings: DownloadArtifact? = null,
    val server: DownloadArtifact? = null,
)

@Serializable
data class DownloadArtifact(
    val path: String? = null,
    val sha1: String? = null,
    val size: Long = 0,
    val url: String,
)

@Serializable
data class JavaVersionInfo(
    val component: String = "java-runtime-gamma",
    val majorVersion: Int = 8,
)

@Serializable
data class LibraryEntry(
    val name: String,
    val downloads: LibraryDownloads? = null,
    val url: String? = null,
    val rules: List<RuleEntry>? = null,
    val natives: Map<String, String>? = null,
    val extract: ExtractConfig? = null,
)

@Serializable
data class LibraryDownloads(
    val artifact: DownloadArtifact? = null,
    val classifiers: Map<String, DownloadArtifact>? = null,
)

@Serializable
data class ExtractConfig(
    val exclude: List<String> = emptyList(),
)

@Serializable
data class RuleEntry(
    val action: String, // "allow" or "disallow"
    val os: OsRule? = null,
    val features: Map<String, Boolean>? = null,
)

@Serializable
data class OsRule(
    val name: String? = null,
    val arch: String? = null,
    val version: String? = null,
)

@Serializable
data class LoggingConfig(
    val client: ClientLogging? = null,
)

@Serializable
data class ClientLogging(
    val argument: String? = null,
    val file: LoggingFileArtifact? = null,
    val type: String? = null,
)

@Serializable
data class LoggingFileArtifact(
    val id: String,
    val sha1: String,
    val size: Long,
    val url: String,
)

@Serializable
data class ArgumentsBlock(
    val game: List<JsonElement> = emptyList(),
    val jvm: List<JsonElement> = emptyList(),
)

@Serializable
data class AssetIndexJson(
    val objects: Map<String, AssetObjectEntry> = emptyMap(),
)

@Serializable
data class AssetObjectEntry(
    val hash: String,
    val size: Long,
)

enum class VersionCategory(val label: String) {
    LATEST_RELEASE("Latest"),
    ALL_RELEASES("Releases"),
    SNAPSHOTS("Snapshots"),
    OLD_RELEASES("Old Releases"),
    HISTORICAL("Historical"),
    EXPERIMENTAL("Experimental"),
    INSTALLED("Installed"),
}

enum class VersionSortOrder(val label: String) {
    NEWEST_FIRST("Newest First"),
    OLDEST_FIRST("Oldest First"),
}
