package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun readAppNameFromContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Noxer Launcher", appName)
    }

    @Test
    fun mainNavigationTabsRenderAndSwitch() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_home").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_versions").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("version_search_input").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_installations").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun accountManagerDistinguishesMicrosoftAndCreatesOfflineProfile() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("top_accounts_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("ACCOUNT MANAGER").assertIsDisplayed()
        composeRule.onNodeWithTag("account_dashboard_card").assertIsDisplayed()
        composeRule.onNodeWithTag("accounts_lazy_list")
            .performScrollToNode(hasTestTag("microsoft_login_button"))
        composeRule.onNodeWithTag("microsoft_login_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("microsoft_signin_overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("open_browser_signin_button").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close Sign-In").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("microsoft_signin_overlay").assertDoesNotExist()

        composeRule.onNodeWithTag("accounts_lazy_list")
            .performScrollToNode(hasTestTag("create_offline_profile_button"))
        composeRule.onNodeWithTag("create_offline_profile_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("offline_username_input").performTextInput("NoxerScout_7")
        composeRule.onNodeWithTag("offline_display_name_input").performTextInput("Scout Profile")
        composeRule.onNodeWithTag("create_offline_lazy_list")
            .performScrollToNode(hasTestTag("save_offline_profile_button"))
        composeRule.onNodeWithTag("save_offline_profile_button").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("accounts_lazy_list")).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Scout Profile").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("accounts_lazy_list")
            .performScrollToNode(hasTestTag("account_dashboard_card"))
        composeRule.onAllNodesWithText("OFFLINE").onFirst().assertIsDisplayed()
    }

    @Test
    fun javaRuntimeManagerScreenRendersArchitectureAndInstallControls() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("open_runtime_manager_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("JAVA RUNTIME MANAGER").assertIsDisplayed()
        composeRule.onNodeWithText("ANDROID DEVICE ARCHITECTURE").assertIsDisplayed()
        composeRule.onNodeWithTag("runtime_manager_list")
            .performScrollToNode(hasTestTag("install_java_primary_button"))
        composeRule.onNodeWithTag("install_java_primary_button").assertIsDisplayed()
        composeRule.onNodeWithTag("select_java_chip_21").assertIsDisplayed()
        composeRule.onNodeWithTag("select_java_chip_17").assertIsDisplayed()
        composeRule.onNodeWithTag("select_java_chip_8").assertIsDisplayed()
        composeRule.onNodeWithTag("custom_java_version_input").assertIsDisplayed()
    }
}
