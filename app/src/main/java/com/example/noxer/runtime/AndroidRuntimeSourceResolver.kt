package com.example.noxer.runtime

import android.os.Build
import com.example.noxer.nativebridge.NoxerNativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.IOException

@Serializable
private data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
private data class GitHubAssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0L,
    val digest: String? = null,
)

internal data class TermuxPackageEntry(
    val packageName: String,
    val version: String,
    val architecture: String,
    val filename: String,
    val sizeBytes: Long,
    val sha256: String,
    val depends: List<String>,
)

data class RuntimeRepositoryEndpoints(
    val githubReleasesUrls: List<String> = DEFAULT_GITHUB_RELEASE_URLS,
    val termuxAptRepoBaseUrls: List<String> = DEFAULT_TERMUX_REPO_URLS,
) {
    companion object {
        val DEFAULT_GITHUB_RELEASE_URLS = listOf(
            "https://api.github.com/repos/MojoLauncher/android-openjdk-build-17-25/releases?per_page=15",
            "https://api.github.com/repos/AngelAuraMC/android-openjdk-build-multiarch/releases?per_page=15",
            "https://api.github.com/repos/PojavLauncherTeam/android-openjdk-build-multiarch/releases?per_page=15",
            "https://api.github.com/repos/itsaky/openjdk-17-android/releases?per_page=10",
        )
        val DEFAULT_TERMUX_REPO_URLS = listOf(
            "https://packages.termux.dev/apt/termux-main",
            "https://mirror.fcix.net/termux/termux-main",
        )
    }
}

/**
 * Resolves genuine Android-Bionic compiled OpenJDK runtimes (NEVER desktop glibc builds)
 * from verified Android multiarch JRE GitHub releases and Termux APT Android Bionic repositories.
 */
class AndroidRuntimeSourceResolver(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val endpoints: RuntimeRepositoryEndpoints = RuntimeRepositoryEndpoints(),
) : RuntimeSourceResolver {

    override fun detectAbiInfo(): AndroidAbiInfo = detectDeviceAbiInfo()

    override suspend fun resolvePackages(
        majorVersion: Int,
        abi: AndroidAbiInfo,
    ): List<ResolvedRuntimePackage> = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<ResolvedRuntimePackage>()
        val errors = mutableListOf<String>()

        // 1. Query verified Android multiarch GitHub repositories (self-contained Bionic JRE tarballs)
        for (repoUrl in endpoints.githubReleasesUrls) {
            runCatching {
                resolveFromGitHubRepo(repoUrl, majorVersion, abi)
            }.onSuccess { found ->
                candidates += found
            }.onFailure { err ->
                errors += "GitHub($repoUrl): ${err.message}"
            }
        }

        // 2. Query Termux official Android Bionic APT repositories (openjdk-17, openjdk-21, openjdk-25, etc.)
        for (aptBase in endpoints.termuxAptRepoBaseUrls) {
            runCatching {
                resolveFromTermuxApt(aptBase, majorVersion, abi)
            }.onSuccess { found ->
                candidates += found
            }.onFailure { err ->
                errors += "Termux($aptBase): ${err.message}"
            }
        }

        val filtered = candidates
            .distinctBy { it.downloadUrl }
            .filterNot { isDisallowedDesktopBuild(it.downloadUrl, it.displayName) }

        if (filtered.isEmpty() && errors.isNotEmpty()) {
            throw IOException(
                "No Android-compatible Java $majorVersion (${abi.primaryAbi}) runtime found. Sources checked: ${errors.joinToString("; ")}"
            )
        }
        filtered
    }

    private fun resolveFromGitHubRepo(
        repoReleasesUrl: String,
        majorVersion: Int,
        abi: AndroidAbiInfo,
    ): List<ResolvedRuntimePackage> {
        val body = fetchText(repoReleasesUrl)
        val releases = json.decodeFromString<List<GitHubReleaseDto>>(body)
            .filter { !it.draft }

        val providerLabel = repoReleasesUrl
            .substringAfter("/repos/", "")
            .substringBefore("/releases", "")
            .ifBlank { "Android OpenJDK Release" }

        val matches = mutableListOf<Pair<Int, ResolvedRuntimePackage>>()

        for (release in releases) {
            val tagOrName = "${release.tagName} ${release.name}"
            for (asset in release.assets) {
                val lowerName = asset.name.lowercase()
                val format = detectArchiveFormat(lowerName) ?: continue
                if (isDisallowedDesktopBuild(asset.browserDownloadUrl, asset.name)) continue
                if (!matchesJavaMajor(lowerName, tagOrName.lowercase(), majorVersion)) continue
                if (!matchesAndroidAbi(lowerName, abi)) continue
                if (lowerName.contains("universal") && release.assets.any { a ->
                        val l = a.name.lowercase()
                        l.startsWith("bin-") && matchesAndroidAbi(l, abi)
                    }
                ) {
                    // Split universal+bin handled via companion package below
                    val binAsset = release.assets.firstOrNull { a ->
                        val l = a.name.lowercase()
                        l.startsWith("bin-") && detectArchiveFormat(l) != null && matchesAndroidAbi(l, abi)
                    }
                    val binCompanion = binAsset?.let {
                        CompanionRuntimePackage(
                            packageName = it.name,
                            version = release.tagName.ifBlank { "$majorVersion" },
                            downloadUrl = it.browserDownloadUrl,
                            sha256 = parseSha256Digest(it.digest),
                            sizeBytes = it.size,
                            format = detectArchiveFormat(it.name.lowercase()) ?: format,
                        )
                    }
                    matches += scoreAsset(lowerName, majorVersion, isSplit = true) to ResolvedRuntimePackage(
                        majorVersion = majorVersion,
                        fullVersion = release.tagName.ifBlank { "Java $majorVersion" },
                        displayName = "Android OpenJDK $majorVersion (${abi.primaryAbi})",
                        providerName = providerLabel,
                        targetAbi = abi.primaryAbi,
                        elfMachineExpected = abi.elfMachineName,
                        downloadUrl = asset.browserDownloadUrl,
                        sha256 = parseSha256Digest(asset.digest),
                        sizeBytes = asset.size,
                        format = format,
                        isSplitUniversalWithBin = binCompanion != null,
                        companionPackages = listOfNotNull(binCompanion),
                    )
                    continue
                }

                // Prefer direct arch-specific JRE tarball
                val pkg = ResolvedRuntimePackage(
                    majorVersion = majorVersion,
                    fullVersion = release.tagName.ifBlank { asset.name },
                    displayName = "Android OpenJDK $majorVersion (${abi.primaryAbi})",
                    providerName = providerLabel,
                    targetAbi = abi.primaryAbi,
                    elfMachineExpected = abi.elfMachineName,
                    downloadUrl = asset.browserDownloadUrl,
                    sha256 = parseSha256Digest(asset.digest),
                    sizeBytes = asset.size,
                    format = format,
                    isSplitUniversalWithBin = false,
                )
                matches += scoreAsset(lowerName, majorVersion, isSplit = false) to pkg
            }
        }

        return matches.sortedByDescending { it.first }.map { it.second }
    }

    private fun resolveFromTermuxApt(
        aptBaseUrl: String,
        majorVersion: Int,
        abi: AndroidAbiInfo,
    ): List<ResolvedRuntimePackage> {
        val base = aptBaseUrl.trimEnd('/')
        val archPath = "$base/dists/stable/main/binary-${abi.termuxArch}"
        val rawPackages = fetchTermuxPackagesIndex(archPath)
        val entries = parseTermuxPackagesIndex(rawPackages)
        if (entries.isEmpty()) return emptyList()

        val byName = entries.groupBy { it.packageName }
        val targetNames = listOf(
            "openjdk-$majorVersion",
            "openjdk-$majorVersion-jre",
            "openjdk-$majorVersion-jdk",
        )
        val rootEntry = targetNames.firstNotNullOfOrNull { name -> byName[name]?.firstOrNull() }
            ?: return emptyList()

        // Collect lightweight native .so dependencies recursively (skipping shell/doc packages)
        val companions = mutableListOf<CompanionRuntimePackage>()
        val visited = mutableSetOf(rootEntry.packageName)
        val queue = ArrayDeque<String>()
        rootEntry.depends.forEach { queue.add(it) }

        while (queue.isNotEmpty() && companions.size < 32) {
            val depName = queue.removeFirst()
            if (!visited.add(depName)) continue
            if (isSkippedTermuxMetaDep(depName)) continue
            val depEntry = byName[depName]?.firstOrNull() ?: continue
            companions += CompanionRuntimePackage(
                packageName = depEntry.packageName,
                version = depEntry.version,
                downloadUrl = "$base/${depEntry.filename.trimStart('/')}",
                sha256 = depEntry.sha256,
                sizeBytes = depEntry.sizeBytes,
                format = RuntimeArchiveFormat.DEB,
            )
            depEntry.depends.forEach { child ->
                if (!visited.contains(child) && !isSkippedTermuxMetaDep(child)) {
                    queue.add(child)
                }
            }
        }

        return listOf(
            ResolvedRuntimePackage(
                majorVersion = majorVersion,
                fullVersion = "OpenJDK ${rootEntry.version}",
                displayName = "Android OpenJDK $majorVersion (${abi.primaryAbi})",
                providerName = "Termux Android Bionic APT",
                targetAbi = abi.primaryAbi,
                elfMachineExpected = abi.elfMachineName,
                downloadUrl = "$base/${rootEntry.filename.trimStart('/')}",
                sha256 = rootEntry.sha256,
                sizeBytes = rootEntry.sizeBytes,
                format = RuntimeArchiveFormat.DEB,
                companionPackages = companions,
            )
        )
    }

    private fun fetchTermuxPackagesIndex(archBaseUrl: String): String {
        val candidates = listOf(
            "$archBaseUrl/Packages.xz",
            "$archBaseUrl/Packages.gz",
            "$archBaseUrl/Packages",
        )
        var lastErr: Throwable? = null
        for (url in candidates) {
            try {
                val bytes = fetchBytes(url)
                return when {
                    url.endsWith(".xz") -> XZInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().decodeToString() }
                    url.endsWith(".gz") -> GzipCompressorInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().decodeToString() }
                    else -> bytes.decodeToString()
                }
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        throw lastErr ?: IOException("Unable to download Termux Packages index")
    }

    private fun fetchText(url: String): String = fetchBytes(url).decodeToString()

    private fun fetchBytes(url: String): ByteArray {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "NoxerLauncher/1.0 (Android)")
            .header("Accept", "application/vnd.github+json, application/octet-stream, text/plain")
            .build()
        return okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} fetching $url")
            resp.body?.bytes() ?: throw IOException("Empty response from $url")
        }
    }

    companion object {
        fun detectDeviceAbiInfo(
            supportedAbis: List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty()
                .ifEmpty { listOf(NoxerNativeBridge.getArchitecture()) },
        ): AndroidAbiInfo {
            val normalized = supportedAbis.map { it.trim() }.filter { it.isNotBlank() }
            val primary = when {
                normalized.any { it.equals("arm64-v8a", ignoreCase = true) || it.equals("aarch64", ignoreCase = true) } -> "arm64-v8a"
                normalized.any { it.equals("x86_64", ignoreCase = true) } -> "x86_64"
                normalized.any { it.equals("armeabi-v7a", ignoreCase = true) } -> "armeabi-v7a"
                normalized.any { it.equals("x86", ignoreCase = true) } -> "x86"
                else -> normalized.firstOrNull() ?: "arm64-v8a"
            }
            val isArm64 = primary == "arm64-v8a"
            return when (primary) {
                "arm64-v8a" -> AndroidAbiInfo(
                    primaryAbi = "arm64-v8a",
                    supportedAbis = normalized,
                    isArm64Compatible = true,
                    elfMachineName = "AArch64",
                    pojavArchAliases = listOf("arm64", "aarch64", "arm64-v8a"),
                    termuxArch = "aarch64",
                )
                "armeabi-v7a" -> AndroidAbiInfo(
                    primaryAbi = "armeabi-v7a",
                    supportedAbis = normalized,
                    isArm64Compatible = false,
                    elfMachineName = "ARM",
                    pojavArchAliases = listOf("arm32", "armeabi-v7a", "arm"),
                    termuxArch = "arm",
                )
                "x86_64" -> AndroidAbiInfo(
                    primaryAbi = "x86_64",
                    supportedAbis = normalized,
                    isArm64Compatible = false,
                    elfMachineName = "x86-64",
                    pojavArchAliases = listOf("x86_64", "x64", "amd64"),
                    termuxArch = "x86_64",
                )
                "x86" -> AndroidAbiInfo(
                    primaryAbi = "x86",
                    supportedAbis = normalized,
                    isArm64Compatible = false,
                    elfMachineName = "x86",
                    pojavArchAliases = listOf("x86", "i686"),
                    termuxArch = "i686",
                )
                else -> AndroidAbiInfo(
                    primaryAbi = primary,
                    supportedAbis = normalized,
                    isArm64Compatible = isArm64,
                    elfMachineName = "AArch64",
                    pojavArchAliases = listOf(primary.lowercase()),
                    termuxArch = "aarch64",
                )
            }
        }

        fun detectArchiveFormat(nameLower: String): RuntimeArchiveFormat? = when {
            nameLower.endsWith(".tar.xz") || nameLower.endsWith(".txz") -> RuntimeArchiveFormat.TAR_XZ
            nameLower.endsWith(".tar.gz") || nameLower.endsWith(".tgz") -> RuntimeArchiveFormat.TAR_GZ
            nameLower.endsWith(".zip") -> RuntimeArchiveFormat.ZIP
            nameLower.endsWith(".deb") -> RuntimeArchiveFormat.DEB
            else -> null
        }

        fun isDisallowedDesktopBuild(url: String, label: String): Boolean {
            val combined = "$url $label".lowercase()
            return combined.contains("api.adoptium.net") ||
                combined.contains("glibc") ||
                combined.contains("alpine") ||
                combined.contains("musl") ||
                combined.contains("windows") ||
                combined.contains("win32") ||
                combined.contains("win64") ||
                combined.contains("macos") ||
                combined.contains("darwin") ||
                (combined.contains("_linux_") && !combined.contains("android") && !combined.contains("bionic"))
        }

        private fun matchesJavaMajor(assetLower: String, releaseLower: String, major: Int): Boolean {
            val m = major.toString()
            val explicitPatterns = listOf(
                Regex("""(?:^|[^0-9])(jre|jdk|openjdk|java)[-_.]?$m(?:[^0-9]|$)"""),
                Regex("""(?:^|[^0-9])u?$m(?:u|b|\.|$)"""),
            )
            if (explicitPatterns.any { it.containsMatchIn(assetLower) }) return true
            // Some repos put version in tag and generic arch name in asset (e.g. "jre-arm64.tar.xz")
            val otherMajors = listOf(8, 11, 16, 17, 21, 22, 23, 24, 25).filter { it != major }
            val mentionsOther = otherMajors.any { other ->
                Regex("""(?:^|[^0-9])(jre|jdk|openjdk|java)[-_.]?$other(?:[^0-9]|$)""").containsMatchIn(assetLower)
            }
            if (mentionsOther) return false
            return explicitPatterns.any { it.containsMatchIn(releaseLower) }
        }

        private fun matchesAndroidAbi(assetLower: String, abi: AndroidAbiInfo): Boolean {
            if (assetLower.contains("universal")) return true
            if (abi.pojavArchAliases.any { alias ->
                    Regex("""(?:^|[^a-z0-9])${Regex.escape(alias)}(?:[^a-z0-9]|$)""").containsMatchIn(assetLower)
                }
            ) return true
            // Check exact boundary to avoid matching "arm64" inside "arm32" or "arm" inside "arm64"
            return when (abi.primaryAbi) {
                "arm64-v8a" -> assetLower.contains("arm64") || assetLower.contains("aarch64")
                "armeabi-v7a" -> (assetLower.contains("arm32") || assetLower.contains("armeabi")) && !assetLower.contains("arm64")
                "x86_64" -> assetLower.contains("x86_64") || assetLower.contains("x64") || assetLower.contains("amd64")
                "x86" -> (assetLower.contains("x86") || assetLower.contains("i686")) && !assetLower.contains("x86_64")
                else -> false
            }
        }

        private fun scoreAsset(nameLower: String, major: Int, isSplit: Boolean): Int {
            var score = 100
            if (!isSplit) score += 40
            if (nameLower.contains("jre")) score += 30
            if (nameLower.contains("$major")) score += 20
            if (nameLower.contains("release")) score += 10
            if (nameLower.endsWith(".tar.xz")) score += 5
            if (nameLower.contains("debug") || nameLower.contains("static") || nameLower.contains("test")) score -= 60
            return score
        }

        private fun parseSha256Digest(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return raw.removePrefix("sha256:").trim().takeIf { it.length == 64 } ?: ""
        }

        private val SKIPPED_DEPS = setOf(
            "bash", "coreutils", "ca-certificates", "ca-certificates-java",
            "dash", "dpkg", "findutils", "sed", "tar", "gzip", "bzip2",
            "termux-tools", "resolv-conf", "xdg-utils", "shared-mime-info",
        )

        private fun isSkippedTermuxMetaDep(name: String): Boolean =
            name in SKIPPED_DEPS || name.endsWith("-doc") || name.endsWith("-dev") || name.contains("static")

        internal fun parseTermuxPackagesIndex(text: String): List<TermuxPackageEntry> {
            if (text.isBlank()) return emptyList()
            val result = mutableListOf<TermuxPackageEntry>()
            val stanzas = text.split(Regex("""\r?\n\s*\r?\n"""))
            for (stanza in stanzas) {
                if (stanza.isBlank()) continue
                var pkg = ""
                var ver = ""
                var arch = ""
                var filename = ""
                var size = 0L
                var sha256 = ""
                var deps = emptyList<String>()
                for (line in stanza.lines()) {
                    val idx = line.indexOf(':')
                    if (idx <= 0) continue
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    when (key) {
                        "Package" -> pkg = value
                        "Version" -> ver = value
                        "Architecture" -> arch = value
                        "Filename" -> filename = value
                        "Size" -> size = value.toLongOrNull() ?: 0L
                        "SHA256" -> sha256 = value
                        "Depends" -> {
                            deps = value.split(",")
                                .mapNotNull { part ->
                                    val firstAlt = part.substringBefore("|").trim()
                                    val clean = firstAlt.substringBefore("(").substringBefore(":").trim()
                                    clean.takeIf { it.isNotBlank() }
                                }
                        }
                    }
                }
                if (pkg.isNotBlank() && filename.isNotBlank()) {
                    result += TermuxPackageEntry(
                        packageName = pkg,
                        version = ver,
                        architecture = arch,
                        filename = filename,
                        sizeBytes = size,
                        sha256 = sha256,
                        depends = deps,
                    )
                }
            }
            return result
        }
    }
}
