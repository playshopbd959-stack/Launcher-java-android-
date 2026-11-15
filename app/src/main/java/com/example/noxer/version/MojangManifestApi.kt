package com.example.noxer.version

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class MojangManifestApi(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    companion object {
        const val MANIFEST_V2_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    }

    suspend fun fetchVersionManifest(): VersionManifestV2 = withContext(Dispatchers.IO) {
        val raw = executeGet(MANIFEST_V2_URL)
        json.decodeFromString(VersionManifestV2.serializer(), raw)
    }

    suspend fun fetchVersionDetails(versionUrl: String): VersionDetails = withContext(Dispatchers.IO) {
        val raw = executeGet(versionUrl)
        json.decodeFromString(VersionDetails.serializer(), raw)
    }

    suspend fun fetchAssetIndex(assetIndexUrl: String): AssetIndexJson = withContext(Dispatchers.IO) {
        val raw = executeGet(assetIndexUrl)
        json.decodeFromString(AssetIndexJson.serializer(), raw)
    }

    suspend fun fetchRawString(url: String): String = withContext(Dispatchers.IO) {
        executeGet(url)
    }

    private fun executeGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "NoxerLauncher/1.0 (Android)")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} fetching $url")
            }
            return response.body?.string() ?: throw IOException("Empty response body from $url")
        }
    }
}
