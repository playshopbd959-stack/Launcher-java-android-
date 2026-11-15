package com.example.noxer.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.noxer.auth.ActiveIdentity
import com.example.noxer.auth.AuthFlowState
import com.example.noxer.auth.MicrosoftAuthManager
import com.example.noxer.data.db.AccountEntity
import com.example.noxer.data.db.OfflineProfileEntity
import com.example.noxer.ui.components.NoxerBadge
import com.example.noxer.ui.components.NoxerPanel
import com.example.ui.theme.NoxerAmber
import com.example.ui.theme.NoxerBlack
import com.example.ui.theme.NoxerBorder
import com.example.ui.theme.NoxerCrimsonContainer
import com.example.ui.theme.NoxerCrimsonGlow
import com.example.ui.theme.NoxerEmerald
import com.example.ui.theme.NoxerNeonRed
import com.example.ui.theme.NoxerObsidian
import com.example.ui.theme.NoxerSurfaceElevated
import com.example.ui.theme.NoxerTextMuted
import com.example.ui.theme.NoxerTextPrimary
import com.example.ui.theme.NoxerTextSecondary
import java.text.DateFormat
import java.util.Date

private fun formatTimestamp(epochMs: Long): String = runCatching {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
}.getOrDefault("Recently")

@Composable
fun AccountsScreen(
    activeIdentity: ActiveIdentity,
    accounts: List<AccountEntity>,
    offlineProfiles: List<OfflineProfileEntity>,
    authFlowState: AuthFlowState,
    onStartMicrosoftLogin: () -> Unit,
    onSubmitAuthCode: (String) -> Unit,
    onFailAuth: (String) -> Unit,
    onCancelAuthFlow: () -> Unit,
    onSelectMicrosoftAccount: (String) -> Unit,
    onRefreshActive: () -> Unit,
    onLogoutMicrosoft: (String) -> Unit,
    onCreateOfflineProfile: () -> Unit,
    onEditOfflineProfile: (OfflineProfileEntity) -> Unit,
    onSelectOfflineProfile: (String) -> Unit,
    onDeleteOfflineProfile: (String) -> Unit,
) {
    val context = LocalContext.current
    var pendingDeleteProfile by remember { mutableStateOf<OfflineProfileEntity?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(NoxerBlack)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(NoxerBlack)
                .testTag("accounts_lazy_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
        item {
            Text("ACCOUNT MANAGER", style = MaterialTheme.typography.headlineSmall, color = NoxerTextPrimary)
            Text(
                text = "Manage verified Microsoft accounts and separated local offline profiles.",
                style = MaterialTheme.typography.bodySmall,
                color = NoxerTextSecondary,
            )
        }

        // 1. ACCOUNT DASHBOARD (Active Identity Summary)
        item {
            AccountDashboardCard(
                activeIdentity = activeIdentity,
                onCreateOfflineProfile = onCreateOfflineProfile,
                onStartMicrosoftLogin = onStartMicrosoftLogin,
                onRefreshActive = onRefreshActive,
                onEditOfflineProfile = onEditOfflineProfile,
                onLogoutMicrosoft = onLogoutMicrosoft,
                onDeleteOfflineProfile = { profile -> pendingDeleteProfile = profile },
            )
        }

        // 2. SECTION: MICROSOFT ACCOUNTS
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("MICROSOFT ACCOUNTS", style = MaterialTheme.typography.titleMedium, color = NoxerTextPrimary)
                    Text(
                        text = "OAuth2 + Xbox Live + XSTS + Ownership Verification",
                        style = MaterialTheme.typography.labelSmall,
                        color = NoxerTextMuted,
                    )
                }
                NoxerBadge("${accounts.size} SIGNED IN", NoxerEmerald)
            }
        }

        // OAuth2 Login / Status Panel
        item {
            NoxerPanel(highlighted = authFlowState !is AuthFlowState.Idle && authFlowState !is AuthFlowState.Authenticated) {
                when (authFlowState) {
                    is AuthFlowState.Idle, is AuthFlowState.Authenticated -> {
                        Text("ADD MICROSOFT ACCOUNT", style = MaterialTheme.typography.labelMedium, color = NoxerNeonRed)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Sign in directly via Microsoft's official OAuth2 page. Noxer never requests or stores passwords and verifies genuine Minecraft: Java Edition ownership.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NoxerTextSecondary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onStartMicrosoftLogin,
                                colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                                modifier = Modifier.testTag("microsoft_login_button"),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Sign In with Microsoft", style = MaterialTheme.typography.labelLarge)
                            }
                            if (accounts.isNotEmpty() && activeIdentity is ActiveIdentity.Microsoft) {
                                OutlinedButton(
                                    onClick = onRefreshActive,
                                    border = BorderStroke(1.dp, NoxerBorder),
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Refresh")
                                }
                            }
                        }
                    }

                    is AuthFlowState.WebSignIn -> {
                        Text("MICROSOFT SIGN-IN ACTIVE", style = MaterialTheme.typography.labelMedium, color = NoxerNeonRed)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Complete your Microsoft sign-in in the embedded secure login window.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NoxerTextSecondary,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onCancelAuthFlow, border = BorderStroke(1.dp, NoxerBorder)) {
                            Text("Cancel")
                        }
                    }

                    is AuthFlowState.RequestingCode -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = NoxerNeonRed)
                            Text("Requesting Microsoft OAuth2 device code...", color = NoxerTextPrimary)
                        }
                    }

                    is AuthFlowState.WaitingForUser -> {
                        Text("ENTER CODE AT MICROSOFT.COM/LINK", style = MaterialTheme.typography.labelMedium, color = NoxerNeonRed)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = authFlowState.userCode,
                            style = MaterialTheme.typography.displaySmall,
                            color = NoxerTextPrimary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(authFlowState.message, style = MaterialTheme.typography.bodySmall, color = NoxerTextSecondary)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Microsoft Device Code", authFlowState.userCode))
                                    val uri = Uri.parse(authFlowState.verificationUri)
                                    runCatching {
                                        CustomTabsIntent.Builder().build().launchUrl(context, uri)
                                    }.onFailure {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                            ) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Copy Code & Open Browser")
                            }
                            OutlinedButton(onClick = onCancelAuthFlow, border = BorderStroke(1.dp, NoxerBorder)) {
                                Text("Cancel")
                            }
                        }
                    }

                    is AuthFlowState.ExchangingTokens -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = NoxerNeonRed)
                            Text(authFlowState.step, color = NoxerTextPrimary)
                        }
                    }

                    is AuthFlowState.Failed -> {
                        Text("AUTHENTICATION FAILED", style = MaterialTheme.typography.labelMedium, color = NoxerCrimsonGlow)
                        Spacer(Modifier.height(4.dp))
                        Text(authFlowState.reason, style = MaterialTheme.typography.bodyMedium, color = NoxerTextPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text("Recovery: ${authFlowState.recovery}", style = MaterialTheme.typography.bodySmall, color = NoxerTextSecondary)
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = onStartMicrosoftLogin,
                            colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                        ) {
                            Text("Retry Sign In")
                        }
                    }
                }
            }
        }

        if (accounts.isEmpty()) {
            item {
                NoxerPanel {
                    Text(
                        text = "No Microsoft accounts signed in yet. Use 'Sign In with Microsoft' above for online-mode servers and official Mojang entitlement verification.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NoxerTextMuted,
                    )
                }
            }
        } else {
            items(accounts, key = { "ms_${it.uuid}" }) { acc ->
                val isSelected = activeIdentity is ActiveIdentity.Microsoft && activeIdentity.account.uuid == acc.uuid
                NoxerPanel(highlighted = isSelected) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = "https://mc-heads.net/avatar/${acc.uuid.replace("-", "")}/128",
                            contentDescription = acc.username,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = acc.username,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = NoxerTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (acc.ownsMinecraft) {
                                    Icon(
                                        Icons.Default.VerifiedUser,
                                        contentDescription = "Verified Account",
                                        tint = NoxerEmerald,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            Text(
                                text = "MICROSOFT ACCOUNT • ${acc.uuid}",
                                style = MaterialTheme.typography.labelSmall,
                                color = NoxerTextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Last authenticated: ${formatTimestamp(acc.lastAuthenticatedAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = NoxerTextSecondary,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (isSelected) NoxerBadge("SELECTED", NoxerNeonRed)
                                if (acc.ownsMinecraft) {
                                    NoxerBadge("VERIFIED ACCOUNT", NoxerEmerald)
                                } else {
                                    NoxerBadge("UNVERIFIED", NoxerCrimsonGlow)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isSelected) {
                            Button(
                                onClick = { onSelectMicrosoftAccount(acc.uuid) },
                                colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                                modifier = Modifier.testTag("select_ms_${acc.uuid}"),
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("SELECT", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        OutlinedButton(
                            onClick = { onLogoutMicrosoft(acc.uuid) },
                            border = BorderStroke(1.dp, NoxerBorder),
                            modifier = Modifier.testTag("logout_ms_${acc.uuid}"),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(15.dp), tint = NoxerCrimsonGlow)
                            Spacer(Modifier.width(4.dp))
                            Text("LOG OUT", style = MaterialTheme.typography.labelSmall, color = NoxerCrimsonGlow)
                        }
                    }
                }
            }
        }

        // 3. SECTION: OFFLINE PROFILES
        item {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("OFFLINE PROFILES", style = MaterialTheme.typography.titleMedium, color = NoxerTextPrimary)
                    Text(
                        text = "UNLICENSED / OFFLINE PROFILE — Local gameplay & offline-mode servers only",
                        style = MaterialTheme.typography.labelSmall,
                        color = NoxerAmber,
                    )
                }
                NoxerBadge("${offlineProfiles.size} LOCAL", NoxerAmber)
            }
        }

        // Offline Policy & Multiplayer Info + Create Button
        item {
            NoxerPanel(highlighted = false) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = NoxerAmber, modifier = Modifier.size(18.dp))
                    Text(
                        text = "Many multiplayer servers require authenticated Microsoft accounts. Offline profiles do not bypass server authentication or Mojang ownership checks and are intended only for offline singleplayer or servers explicitly configured for offline mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NoxerTextSecondary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onCreateOfflineProfile,
                    colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("create_offline_profile_button"),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("CREATE OFFLINE PROFILE", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        if (offlineProfiles.isEmpty()) {
            item {
                NoxerPanel {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Default.PersonOff, contentDescription = null, tint = NoxerTextMuted, modifier = Modifier.size(22.dp))
                        Text(
                            text = "No offline profiles created yet. Tap 'CREATE OFFLINE PROFILE' above to set up a local player identity.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NoxerTextMuted,
                        )
                    }
                }
            }
        } else {
            items(offlineProfiles, key = { "off_${it.id}" }) { profile ->
                val isSelected = activeIdentity is ActiveIdentity.Offline && activeIdentity.profile.id == profile.id
                NoxerPanel(
                    highlighted = isSelected,
                    modifier = Modifier.testTag("offline_profile_card_${profile.username}"),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OfflineProfileAvatar(iconId = profile.iconId, sizeDp = 48, highlighted = isSelected)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = NoxerTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "@${profile.username} • UNLICENSED / OFFLINE PROFILE",
                                style = MaterialTheme.typography.labelSmall,
                                color = NoxerAmber,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Local UUID: ${profile.id}",
                                style = MaterialTheme.typography.labelSmall,
                                color = NoxerTextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Last used: ${formatTimestamp(profile.lastUsedAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = NoxerTextSecondary,
                            )
                            if (profile.notes.isNotBlank()) {
                                Text(
                                    text = profile.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NoxerTextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (isSelected) NoxerBadge("SELECTED", NoxerNeonRed)
                                NoxerBadge("OFFLINE", NoxerAmber)
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isSelected) {
                            Button(
                                onClick = { onSelectOfflineProfile(profile.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                                modifier = Modifier.testTag("select_offline_${profile.username}"),
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("SELECT", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        OutlinedButton(
                            onClick = { onEditOfflineProfile(profile) },
                            border = BorderStroke(1.dp, NoxerBorder),
                            modifier = Modifier.testTag("edit_offline_${profile.username}"),
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("EDIT", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = { pendingDeleteProfile = profile },
                            border = BorderStroke(1.dp, NoxerBorder),
                            modifier = Modifier.testTag("delete_offline_${profile.username}"),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp), tint = NoxerCrimsonGlow)
                            Spacer(Modifier.width(4.dp))
                            Text("DELETE", style = MaterialTheme.typography.labelSmall, color = NoxerCrimsonGlow)
                        }
                    }
                }
            }
        }
    }

    pendingDeleteProfile?.let { profileToDelete ->
        AlertDialog(
            onDismissRequest = { pendingDeleteProfile = null },
            containerColor = NoxerSurfaceElevated,
            title = {
                Text("Delete Offline Profile?", color = NoxerTextPrimary, style = MaterialTheme.typography.titleLarge)
            },
            text = {
                Text(
                    "Delete local offline profile '${profileToDelete.displayName}' (@${profileToDelete.username})? Your world saves inside installations will remain intact.",
                    color = NoxerTextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteOfflineProfile(profileToDelete.id)
                        pendingDeleteProfile = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                    modifier = Modifier.testTag("confirm_delete_offline_button"),
                ) {
                    Text("DELETE")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { pendingDeleteProfile = null },
                    border = BorderStroke(1.dp, NoxerBorder),
                ) {
                    Text("Cancel", color = NoxerTextSecondary)
                }
            },
        )
    }

        if (authFlowState is AuthFlowState.WebSignIn) {
            MicrosoftWebSignInOverlay(
                authUrl = authFlowState.authUrl,
                redirectPrefix = authFlowState.redirectUriPrefix,
                onCodeReceived = onSubmitAuthCode,
                onError = onFailAuth,
                onDismiss = onCancelAuthFlow,
            )
        }
    }
}

@Composable
private fun AccountDashboardCard(
    activeIdentity: ActiveIdentity,
    onCreateOfflineProfile: () -> Unit,
    onStartMicrosoftLogin: () -> Unit,
    onRefreshActive: () -> Unit,
    onEditOfflineProfile: (OfflineProfileEntity) -> Unit,
    onLogoutMicrosoft: (String) -> Unit,
    onDeleteOfflineProfile: (OfflineProfileEntity) -> Unit,
) {
    NoxerPanel(
        highlighted = true,
        modifier = Modifier.testTag("account_dashboard_card"),
    ) {
        Text("ACCOUNT DASHBOARD", style = MaterialTheme.typography.labelMedium, color = NoxerNeonRed)
        Spacer(Modifier.height(10.dp))

        when (activeIdentity) {
            is ActiveIdentity.Microsoft -> {
                val acc = activeIdentity.account
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = "https://mc-heads.net/avatar/${acc.uuid.replace("-", "")}/128",
                        contentDescription = acc.username,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(acc.username, style = MaterialTheme.typography.titleLarge, color = NoxerTextPrimary)
                        Text("Account Type: Microsoft Account", style = MaterialTheme.typography.bodySmall, color = NoxerTextSecondary)
                        Text(
                            text = "Profile ID: ${acc.uuid}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NoxerTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Last used: ${formatTimestamp(acc.lastAuthenticatedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NoxerTextSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NoxerBadge("SELECTED PROFILE", NoxerNeonRed)
                    if (acc.ownsMinecraft) {
                        NoxerBadge("VERIFIED ACCOUNT", NoxerEmerald)
                    } else {
                        NoxerBadge("UNVERIFIED", NoxerCrimsonGlow)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefreshActive, border = BorderStroke(1.dp, NoxerBorder)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("REFRESH", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onClick = onCreateOfflineProfile, border = BorderStroke(1.dp, NoxerBorder)) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("CREATE PROFILE", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onClick = { onLogoutMicrosoft(acc.uuid) }, border = BorderStroke(1.dp, NoxerBorder)) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(15.dp), tint = NoxerCrimsonGlow)
                        Spacer(Modifier.width(4.dp))
                        Text("LOG OUT", style = MaterialTheme.typography.labelSmall, color = NoxerCrimsonGlow)
                    }
                }
            }

            is ActiveIdentity.Offline -> {
                val profile = activeIdentity.profile
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OfflineProfileAvatar(iconId = profile.iconId, sizeDp = 56, highlighted = true)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile.displayName, style = MaterialTheme.typography.titleLarge, color = NoxerTextPrimary)
                        Text(
                            text = "@${profile.username} • UNLICENSED / OFFLINE PROFILE",
                            style = MaterialTheme.typography.bodySmall,
                            color = NoxerAmber,
                        )
                        Text(
                            text = "Profile ID: ${profile.id}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NoxerTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Last used: ${formatTimestamp(profile.lastUsedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NoxerTextSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NoxerBadge("SELECTED PROFILE", NoxerNeonRed)
                    NoxerBadge("OFFLINE", NoxerAmber)
                    NoxerBadge("NOT LICENSED", NoxerCrimsonGlow)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onEditOfflineProfile(profile) }, border = BorderStroke(1.dp, NoxerBorder)) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("EDIT", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onClick = onCreateOfflineProfile, border = BorderStroke(1.dp, NoxerBorder)) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("CREATE PROFILE", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onClick = { onDeleteOfflineProfile(profile) }, border = BorderStroke(1.dp, NoxerBorder)) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp), tint = NoxerCrimsonGlow)
                        Spacer(Modifier.width(4.dp))
                        Text("DELETE", style = MaterialTheme.typography.labelSmall, color = NoxerCrimsonGlow)
                    }
                }
            }

            is ActiveIdentity.None -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        color = NoxerObsidian,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, NoxerBorder),
                        modifier = Modifier.size(52.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = NoxerTextMuted, modifier = Modifier.size(30.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("No Active Profile Selected", style = MaterialTheme.typography.titleMedium, color = NoxerTextPrimary)
                        Text(
                            text = "Sign in with a Microsoft account for verified online play or create a local offline profile.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NoxerTextSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onStartMicrosoftLogin,
                        colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                    ) {
                        Text("SIGN IN", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onClick = onCreateOfflineProfile, border = BorderStroke(1.dp, NoxerBorder)) {
                        Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("CREATE PROFILE", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MicrosoftWebSignInOverlay(
    authUrl: String,
    redirectPrefix: String,
    onCodeReceived: (String) -> Unit,
    onError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val context = LocalContext.current
    var handledRedirect by remember(authUrl) { mutableStateOf(false) }
    var rendererCrashed by remember(authUrl) { mutableStateOf(false) }
    var retryNonce by remember(authUrl) { mutableIntStateOf(0) }
    var manualInput by remember(authUrl) { mutableStateOf("") }
    var manualInputError by remember(authUrl) { mutableStateOf<String?>(null) }

    fun openInExternalBrowser() {
        val uri = Uri.parse(authUrl)
        runCatching {
            CustomTabsIntent.Builder().build().launchUrl(context, uri)
        }.onFailure {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .testTag("microsoft_signin_overlay"),
        color = NoxerSurfaceElevated,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, NoxerBorder),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Microsoft Sign-In", style = MaterialTheme.typography.titleMedium, color = NoxerTextPrimary)
                    Text("login.live.com", style = MaterialTheme.typography.labelSmall, color = NoxerTextSecondary)
                }
                OutlinedButton(
                    onClick = { openInExternalBrowser() },
                    border = BorderStroke(1.dp, NoxerBorder),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("open_browser_signin_button"),
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Browser", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close Sign-In", tint = NoxerTextSecondary)
                }
            }

            if (rendererCrashed) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "WEBVIEW RENDERER RECOVERED",
                        style = MaterialTheme.typography.labelMedium,
                        color = NoxerAmber,
                    )
                    Text(
                        text = "The device WebView renderer process terminated unexpectedly. You can retry the embedded sign-in window or sign in using your system browser and paste the final redirect URL or authorization code below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NoxerTextSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                rendererCrashed = false
                                handledRedirect = false
                                retryNonce++
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Retry WebView")
                        }
                        OutlinedButton(
                            onClick = { openInExternalBrowser() },
                            border = BorderStroke(1.dp, NoxerBorder),
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Open System Browser")
                        }
                    }

                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = {
                            manualInput = it
                            manualInputError = null
                        },
                        label = { Text("Redirect URL or Auth Code") },
                        placeholder = { Text("https://login.live.com/oauth20_desktop.srf?code=...") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NoxerNeonRed,
                            unfocusedBorderColor = NoxerBorder,
                            focusedTextColor = NoxerTextPrimary,
                            unfocusedTextColor = NoxerTextPrimary,
                            focusedContainerColor = NoxerObsidian,
                            unfocusedContainerColor = NoxerObsidian,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (manualInputError != null) {
                        Text(
                            text = manualInputError.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = NoxerCrimsonGlow,
                        )
                    }
                    Button(
                        onClick = {
                            val trimmed = manualInput.trim()
                            val extractedError = MicrosoftAuthManager.extractErrorFromRedirectUri(trimmed)
                            val extractedCode = MicrosoftAuthManager.extractCodeFromRedirectUri(trimmed)
                                ?: trimmed.takeIf { it.isNotBlank() && !it.startsWith("http") }
                            when {
                                extractedError != null -> onError(extractedError)
                                extractedCode != null -> onCodeReceived(extractedCode)
                                else -> manualInputError = "Paste the redirect URL containing ?code=... or the authorization code."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NoxerNeonRed),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Complete Microsoft Sign-In")
                    }
                }
            } else {
                key(authUrl, retryNonce) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                isFocusable = true
                                isFocusableInTouchMode = true
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                                }
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                webViewClient = object : WebViewClient() {
                                    private fun inspectUrl(url: String): Boolean {
                                        if (handledRedirect || !url.startsWith(redirectPrefix)) return false
                                        handledRedirect = true
                                        val code = MicrosoftAuthManager.extractCodeFromRedirectUri(url)
                                        val err = MicrosoftAuthManager.extractErrorFromRedirectUri(url)
                                        post {
                                            when {
                                                code != null -> onCodeReceived(code)
                                                err != null -> onError(err)
                                                else -> onError("Microsoft sign-in redirected without authorization code.")
                                            }
                                        }
                                        return true
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        val url = request?.url?.toString().orEmpty()
                                        return inspectUrl(url)
                                    }

                                    override fun onPageStarted(
                                        view: WebView?,
                                        url: String?,
                                        favicon: android.graphics.Bitmap?,
                                    ) {
                                        if (url != null) inspectUrl(url)
                                    }

                                    override fun onRenderProcessGone(
                                        view: WebView?,
                                        detail: RenderProcessGoneDetail?,
                                    ): Boolean {
                                        if (view != null) {
                                            (view.parent as? ViewGroup)?.removeView(view)
                                            runCatching {
                                                view.stopLoading()
                                                view.destroy()
                                            }
                                        }
                                        rendererCrashed = true
                                        return true
                                    }
                                }
                                loadUrl(authUrl)
                            }
                        },
                        onRelease = { webView ->
                            runCatching {
                                webView.stopLoading()
                                webView.webViewClient = WebViewClient()
                                webView.loadUrl("about:blank")
                                webView.removeAllViews()
                                webView.destroy()
                            }
                        },
                    )
                }
            }
        }
    }
}
