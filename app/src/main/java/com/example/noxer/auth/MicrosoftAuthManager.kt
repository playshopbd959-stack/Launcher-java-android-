package com.example.noxer.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.BuildConfig
import com.example.noxer.console.ConsoleLogger
import com.example.noxer.data.db.AccountDao
import com.example.noxer.data.db.AccountEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class DeviceCodeResponse(
    @SerialName("user_code") val userCode: String,
    @SerialName("device_code") val deviceCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Int = 900,
    val interval: Int = 5,
    val message: String = "",
)

@Serializable
private data class MsTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
private data class XblAuthRequest(
    @SerialName("Properties") val properties: XblProperties,
    @SerialName("RelyingParty") val relyingParty: String = "http://auth.xboxlive.com",
    @SerialName("TokenType") val tokenType: String = "JWT",
)

@Serializable
private data class XblProperties(
    @SerialName("AuthMethod") val authMethod: String = "RPS",
    @SerialName("SiteName") val siteName: String = "user.auth.xboxlive.com",
    @SerialName("RpsTicket") val rpsTicket: String,
)

@Serializable
private data class XstsRequest(
    @SerialName("Properties") val properties: XstsProperties,
    @SerialName("RelyingParty") val relyingParty: String = "rp://api.minecraftservices.com/",
    @SerialName("TokenType") val tokenType: String = "JWT",
)

@Serializable
private data class XstsProperties(
    @SerialName("SandboxId") val sandboxId: String = "RETAIL",
    @SerialName("UserTokens") val userTokens: List<String>,
)

@Serializable
private data class XboxAuthResponse(
    @SerialName("Token") val token: String,
    @SerialName("DisplayClaims") val displayClaims: XboxDisplayClaims,
)

@Serializable
private data class XboxDisplayClaims(
    val xui: List<XboxXuiItem> = emptyList(),
)

@Serializable
private data class XboxXuiItem(
    val uhs: String = "",
)

@Serializable
private data class McLoginRequest(
    val identityToken: String,
)

@Serializable
private data class McLoginResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = 86400,
)

@Serializable
private data class McEntitlementsResponse(
    val items: List<McEntitlementItem> = emptyList(),
)

@Serializable
private data class McEntitlementItem(
    val name: String = "",
)

@Serializable
private data class McProfileResponse(
    val id: String,
    val name: String,
    val skins: List<McSkinEntry> = emptyList(),
)

@Serializable
private data class McSkinEntry(
    val url: String? = null,
    val state: String? = null,
)

sealed interface AuthFlowState {
    data object Idle : AuthFlowState
    data class WebSignIn(
        val authUrl: String,
        val redirectUriPrefix: String,
    ) : AuthFlowState
    data object RequestingCode : AuthFlowState
    data class WaitingForUser(
        val userCode: String,
        val verificationUri: String,
        val message: String,
    ) : AuthFlowState
    data class ExchangingTokens(val step: String) : AuthFlowState
    data class Authenticated(val username: String, val uuid: String) : AuthFlowState
    data class Failed(val reason: String, val recovery: String) : AuthFlowState
}

object AccountCrypto {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "noxer_account_aes_key_v1"

    private fun getOrCreateKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            gen.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            gen.generateKey()
        }
    }.getOrNull()

    fun encrypt(plainText: String): String {
        val key = getOrCreateKey() ?: return "PLAIN:${Base64.encodeToString(plainText.toByteArray(), Base64.NO_WRAP)}"
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val data = Base64.encodeToString(cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            "GCM:$iv:$data"
        }.getOrElse {
            "PLAIN:${Base64.encodeToString(plainText.toByteArray(), Base64.NO_WRAP)}"
        }
    }

    fun decrypt(cipherText: String): String {
        if (cipherText.startsWith("PLAIN:")) {
            return String(Base64.decode(cipherText.removePrefix("PLAIN:"), Base64.NO_WRAP), Charsets.UTF_8)
        }
        if (cipherText.startsWith("GCM:")) {
            val parts = cipherText.split(":")
            if (parts.size == 3) {
                val key = getOrCreateKey() ?: return ""
                val iv = Base64.decode(parts[1], Base64.NO_WRAP)
                val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                return String(cipher.doFinal(encrypted), Charsets.UTF_8)
            }
        }
        return ""
    }
}

class MicrosoftAuthManager(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val accountDao: AccountDao,
    private val consoleLogger: ConsoleLogger,
) {
    private val _authState = MutableStateFlow<AuthFlowState>(AuthFlowState.Idle)
    val authState: StateFlow<AuthFlowState> = _authState.asStateFlow()

    val accounts: Flow<List<AccountEntity>> = accountDao.observeAll()
    val activeAccount: Flow<AccountEntity?> = accountDao.observeActive()

    @Volatile
    private var cancelPolling = false

    private fun clientId(): String {
        val configured = runCatching { BuildConfig.MS_CLIENT_ID }.getOrNull()?.trim().orEmpty()
        return configured.ifBlank { LIVE_CLIENT_ID }
    }

    fun hasCustomAzureClientId(): Boolean = clientId() != LIVE_CLIENT_ID

    fun resetAuthFlow() {
        cancelPolling = true
        _authState.value = AuthFlowState.Idle
    }

    fun startWebSignIn() {
        cancelPolling = true
        _authState.value = AuthFlowState.WebSignIn(
            authUrl = buildLiveAuthUrl(clientId()),
            redirectUriPrefix = LIVE_REDIRECT_URI,
        )
    }

    fun failWebSignIn(reason: String) {
        consoleLogger.error("Auth", "Web sign-in failed: $reason")
        _authState.value = AuthFlowState.Failed(
            reason = reason,
            recovery = "Retry Microsoft sign-in and complete consent in the browser window.",
        )
    }

    suspend fun completeWebAuthCode(code: String): Result<AccountEntity> = withContext(Dispatchers.IO) {
        _authState.value = AuthFlowState.ExchangingTokens("Exchanging Microsoft authorization code...")
        try {
            val cid = clientId()
            val tokenUrl = if (cid == LIVE_CLIENT_ID) {
                "https://login.live.com/oauth20_token.srf"
            } else {
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
            }
            val bodyBuilder = FormBody.Builder()
                .add("client_id", cid)
                .add("code", code)
                .add("grant_type", "authorization_code")
                .add("redirect_uri", LIVE_REDIRECT_URI)
            if (cid != LIVE_CLIENT_ID) {
                bodyBuilder.add("scope", "XboxLive.signin offline_access")
            }
            val req = Request.Builder()
                .url(tokenUrl)
                .post(bodyBuilder.build())
                .build()
            val tokenResp = json.decodeFromString(MsTokenResponse.serializer(), executeRequest(req))
            if (tokenResp.error != null) {
                throw IOException(tokenResp.errorDescription ?: tokenResp.error)
            }
            val accessToken = tokenResp.accessToken ?: throw IOException("Missing Microsoft access token")
            val refreshToken = tokenResp.refreshToken.orEmpty()

            val account = completeXboxAndMinecraftChain(accessToken, refreshToken)
            _authState.value = AuthFlowState.Authenticated(account.username, account.uuid)
            Result.success(account)
        } catch (t: Throwable) {
            val recovery = recoveryFor(t)
            consoleLogger.error("Auth", "Microsoft code exchange failed: ${t.message}")
            _authState.value = AuthFlowState.Failed(t.message ?: "Authentication failed", recovery)
            Result.failure(t)
        }
    }

    suspend fun startDeviceCodeLogin(): Result<AccountEntity> = withContext(Dispatchers.IO) {
        if (!hasCustomAzureClientId()) {
            // Microsoft's consumers v2.0/devicecode rejects legacy client 00000000402b5328 with AADSTS700016.
            // Use Live Connect Web Sign-In automatically unless a custom Entra v2.0 MS_CLIENT_ID is configured.
            startWebSignIn()
            return@withContext Result.failure(IOException("Switched to Microsoft Web Sign-In"))
        }
        cancelPolling = false
        _authState.value = AuthFlowState.RequestingCode
        try {
            val cid = clientId()
            val dcBody = FormBody.Builder()
                .add("client_id", cid)
                .add("scope", "XboxLive.signin offline_access")
                .build()
            val dcReq = Request.Builder()
                .url("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode")
                .post(dcBody)
                .build()
            val dcRaw = executeRequest(dcReq)
            val deviceCode = json.decodeFromString(DeviceCodeResponse.serializer(), dcRaw)

            consoleLogger.info("Auth", "Device code generated (${deviceCode.userCode}). Waiting for user browser verification at ${deviceCode.verificationUri}")
            _authState.value = AuthFlowState.WaitingForUser(
                userCode = deviceCode.userCode,
                verificationUri = deviceCode.verificationUri,
                message = deviceCode.message,
            )

            val deadlineMs = System.currentTimeMillis() + deviceCode.expiresIn * 1000L
            var intervalMs = (deviceCode.interval.coerceAtLeast(3)) * 1000L
            var msToken: MsTokenResponse? = null

            while (System.currentTimeMillis() < deadlineMs && !cancelPolling) {
                delay(intervalMs)
                if (cancelPolling) break
                val pollBody = FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .add("client_id", cid)
                    .add("device_code", deviceCode.deviceCode)
                    .build()
                val pollReq = Request.Builder()
                    .url("https://login.microsoftonline.com/consumers/oauth2/v2.0/token")
                    .post(pollBody)
                    .build()
                val raw = okHttpClient.newCall(pollReq).execute().use { it.body?.string().orEmpty() }
                val tokenResp = json.decodeFromString(MsTokenResponse.serializer(), raw)
                when (tokenResp.error) {
                    null -> {
                        msToken = tokenResp
                        break
                    }
                    "authorization_pending" -> continue
                    "slow_down" -> intervalMs += 3000L
                    else -> throw IOException(tokenResp.errorDescription ?: tokenResp.error)
                }
            }

            if (cancelPolling) {
                _authState.value = AuthFlowState.Idle
                return@withContext Result.failure(IOException("Authentication cancelled by user"))
            }

            val finalMsToken = msToken ?: throw IOException("Device code expired before sign-in completed")
            val accessToken = finalMsToken.accessToken ?: throw IOException("Missing Microsoft access token")
            val refreshToken = finalMsToken.refreshToken.orEmpty()

            val account = completeXboxAndMinecraftChain(accessToken, refreshToken)
            _authState.value = AuthFlowState.Authenticated(account.username, account.uuid)
            Result.success(account)
        } catch (t: Throwable) {
            val recovery = recoveryFor(t)
            consoleLogger.error("Auth", "Microsoft login failed: ${t.message}")
            _authState.value = AuthFlowState.Failed(t.message ?: "Authentication failed", recovery)
            Result.failure(t)
        }
    }

    private fun recoveryFor(t: Throwable): String = when {
        t.message?.contains("401") == true || t.message?.contains("ownership", ignoreCase = true) == true ->
            "Ensure this Microsoft account owns Minecraft: Java Edition on minecraft.net."
        t.message?.contains("XSTS", ignoreCase = true) == true || t.message?.contains("2148916233") == true ->
            "Check Xbox Live account age/region settings or create an Xbox Gamertag at xbox.com."
        t.message?.contains("AADSTS700016", ignoreCase = true) == true ->
            "Configured MS_CLIENT_ID was rejected by Entra v2.0. Use standard Microsoft Web Sign-In or a registered public Azure App ID."
        else -> "Verify network connectivity and complete sign-in with your Microsoft account."
    }

    suspend fun refreshActiveAccount(): Result<AccountEntity> = withContext(Dispatchers.IO) {
        val current = accountDao.getActive() ?: return@withContext Result.failure(IOException("No active account"))
        val refreshToken = AccountCrypto.decrypt(current.encryptedMsRefreshToken)
        if (refreshToken.isBlank()) {
            return@withContext Result.failure(IOException("Stored refresh token missing; please sign in again."))
        }
        return@withContext try {
            val cid = clientId()
            val tokenUrl = if (cid == LIVE_CLIENT_ID) {
                "https://login.live.com/oauth20_token.srf"
            } else {
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
            }
            val bodyBuilder = FormBody.Builder()
                .add("client_id", cid)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
            if (cid == LIVE_CLIENT_ID) {
                bodyBuilder.add("redirect_uri", LIVE_REDIRECT_URI)
            } else {
                bodyBuilder.add("scope", "XboxLive.signin offline_access")
            }
            val req = Request.Builder()
                .url(tokenUrl)
                .post(bodyBuilder.build())
                .build()
            val tokenResp = json.decodeFromString(MsTokenResponse.serializer(), executeRequest(req))
            val msAccess = tokenResp.accessToken ?: throw IOException("Refresh failed: ${tokenResp.errorDescription}")
            val account = completeXboxAndMinecraftChain(msAccess, tokenResp.refreshToken ?: refreshToken)
            Result.success(account)
        } catch (t: Throwable) {
            consoleLogger.error("Auth", "Token refresh failed for ${current.username}: ${t.message}")
            Result.failure(t)
        }
    }

    private suspend fun completeXboxAndMinecraftChain(
        msAccessToken: String,
        msRefreshToken: String,
    ): AccountEntity {
        val jsonMedia = "application/json; charset=utf-8".toMediaType()

        // 1. Xbox Live Authenticate (try d= ticket first, fallback to t= for Live Connect tokens if needed)
        _authState.value = AuthFlowState.ExchangingTokens("Authenticating with Xbox Live...")
        val xblResp = runCatching {
            val xblPayload = json.encodeToString(
                XblAuthRequest.serializer(),
                XblAuthRequest(properties = XblProperties(rpsTicket = "d=$msAccessToken")),
            )
            val xblReq = Request.Builder()
                .url("https://user.auth.xboxlive.com/user/authenticate")
                .post(xblPayload.toRequestBody(jsonMedia))
                .header("Accept", "application/json")
                .build()
            json.decodeFromString(XboxAuthResponse.serializer(), executeRequest(xblReq))
        }.getOrElse {
            val fallbackPayload = json.encodeToString(
                XblAuthRequest.serializer(),
                XblAuthRequest(properties = XblProperties(rpsTicket = "t=$msAccessToken")),
            )
            val fallbackReq = Request.Builder()
                .url("https://user.auth.xboxlive.com/user/authenticate")
                .post(fallbackPayload.toRequestBody(jsonMedia))
                .header("Accept", "application/json")
                .build()
            json.decodeFromString(XboxAuthResponse.serializer(), executeRequest(fallbackReq))
        }
        val userHash = xblResp.displayClaims.xui.firstOrNull()?.uhs ?: throw IOException("Missing Xbox userhash (uhs)")

        // 2. XSTS Authorize
        _authState.value = AuthFlowState.ExchangingTokens("Authorizing XSTS security token...")
        val xstsPayload = json.encodeToString(
            XstsRequest.serializer(),
            XstsRequest(properties = XstsProperties(userTokens = listOf(xblResp.token))),
        )
        val xstsReq = Request.Builder()
            .url("https://xsts.auth.xboxlive.com/xsts/authorize")
            .post(xstsPayload.toRequestBody(jsonMedia))
            .header("Accept", "application/json")
            .build()
        val xstsResp = json.decodeFromString(XboxAuthResponse.serializer(), executeRequest(xstsReq))

        // 3. Minecraft Services Login with Xbox
        _authState.value = AuthFlowState.ExchangingTokens("Logging into Minecraft Services...")
        val mcLoginPayload = json.encodeToString(
            McLoginRequest.serializer(),
            McLoginRequest(identityToken = "XBL3.0 x=$userHash;${xstsResp.token}"),
        )
        val mcLoginReq = Request.Builder()
            .url("https://api.minecraftservices.com/authentication/login_with_xbox")
            .post(mcLoginPayload.toRequestBody(jsonMedia))
            .build()
        val mcLoginResp = json.decodeFromString(McLoginResponse.serializer(), executeRequest(mcLoginReq))

        // 4. Ownership Verification (Strict — never bypassed!)
        _authState.value = AuthFlowState.ExchangingTokens("Verifying Minecraft: Java Edition ownership...")
        val entReq = Request.Builder()
            .url("https://api.minecraftservices.com/entitlements/mcstore")
            .header("Authorization", "Bearer ${mcLoginResp.accessToken}")
            .get()
            .build()
        val entitlements = json.decodeFromString(McEntitlementsResponse.serializer(), executeRequest(entReq))
        val ownsGame = entitlements.items.any {
            it.name == "product_minecraft" || it.name == "game_minecraft"
        }
        if (!ownsGame) {
            throw IOException("Account does not own Minecraft: Java Edition (entitlements/mcstore returned no game license)")
        }

        // 5. Minecraft Profile Retrieval
        _authState.value = AuthFlowState.ExchangingTokens("Retrieving Minecraft profile...")
        val profileReq = Request.Builder()
            .url("https://api.minecraftservices.com/minecraft/profile")
            .header("Authorization", "Bearer ${mcLoginResp.accessToken}")
            .get()
            .build()
        val profile = json.decodeFromString(McProfileResponse.serializer(), executeRequest(profileReq))
        val activeSkin = profile.skins.firstOrNull { it.state.equals("ACTIVE", ignoreCase = true) }?.url
            ?: profile.skins.firstOrNull()?.url

        val entity = AccountEntity(
            uuid = formatUuid(profile.id),
            username = profile.name,
            encryptedMcAccessToken = AccountCrypto.encrypt(mcLoginResp.accessToken),
            encryptedMsRefreshToken = AccountCrypto.encrypt(msRefreshToken),
            encryptedXstsHash = AccountCrypto.encrypt(userHash),
            expiresAtEpochSec = (System.currentTimeMillis() / 1000L) + mcLoginResp.expiresIn,
            skinUrl = activeSkin,
            ownsMinecraft = true,
            isActive = true,
            lastAuthenticatedAt = System.currentTimeMillis(),
        )
        accountDao.clearActive()
        accountDao.upsert(entity)
        consoleLogger.info("Auth", "Authenticated Minecraft profile ${entity.username} (${entity.uuid}) with verified ownership.")
        return entity
    }

    suspend fun getStoredActiveAccount(): AccountEntity? = accountDao.getActive()

    suspend fun switchAccount(uuid: String) {
        accountDao.switchActiveAccount(uuid)
    }

    suspend fun logoutAccount(uuid: String) {
        accountDao.deleteByUuid(uuid)
    }

    fun decryptAccessToken(account: AccountEntity): String =
        AccountCrypto.decrypt(account.encryptedMcAccessToken)

    private fun executeRequest(request: Request): String {
        okHttpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} from ${request.url.host}: ${bodyStr.take(240)}")
            }
            return bodyStr
        }
    }

    companion object {
        const val LIVE_CLIENT_ID = "00000000402b5328"
        const val LIVE_REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf"

        fun buildLiveAuthUrl(clientId: String = LIVE_CLIENT_ID): String {
            val encodedRedirect = java.net.URLEncoder.encode(LIVE_REDIRECT_URI, "UTF-8")
            val encodedScope = java.net.URLEncoder.encode("XboxLive.signin offline_access", "UTF-8")
            return "https://login.live.com/oauth20_authorize.srf" +
                "?client_id=$clientId" +
                "&response_type=code" +
                "&redirect_uri=$encodedRedirect" +
                "&scope=$encodedScope" +
                "&prompt=select_account"
        }

        fun extractCodeFromRedirectUri(uriString: String): String? {
            if (!uriString.startsWith(LIVE_REDIRECT_URI)) return null
            val query = uriString.substringAfter('?', "")
            if (query.isBlank()) return null
            return query.split('&')
                .mapNotNull { pair ->
                    val idx = pair.indexOf('=')
                    if (idx > 0) pair.substring(0, idx) to java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    else null
                }
                .firstOrNull { it.first == "code" }
                ?.second
                ?.takeIf { it.isNotBlank() }
        }

        fun extractErrorFromRedirectUri(uriString: String): String? {
            if (!uriString.startsWith(LIVE_REDIRECT_URI)) return null
            val query = uriString.substringAfter('?', "")
            if (query.isBlank()) return null
            val params = query.split('&').mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) pair.substring(0, idx) to java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                else null
            }.toMap()
            val err = params["error"] ?: return null
            val desc = params["error_description"]
            return if (!desc.isNullOrBlank()) "$err: $desc" else err
        }

        fun formatUuid(raw32: String): String {
            val clean = raw32.replace("-", "")
            if (clean.length != 32) return raw32
            return "${clean.substring(0, 8)}-${clean.substring(8, 12)}-${clean.substring(12, 16)}-${clean.substring(16, 20)}-${clean.substring(20, 32)}"
        }
    }
}
