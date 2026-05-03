package eu.hyperhdr.android.tv.compose

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.ExperimentalTvMaterial3Api
import eu.hyperhdr.android.tv.compose.screens.SettingsScreen
import eu.hyperhdr.android.tv.compose.support.StubBinder
import eu.hyperhdr.android.tv.compose.theme.HyperHdrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenSmokeTest {

    @get:Rule val composeTestRule = createComposeRule()

    @OptIn(ExperimentalTvMaterial3Api::class)
    @Test
    fun settingsScreenRendersAllFiveCategories() {
        composeTestRule.setContent {
            HyperHdrTheme { SettingsScreen(stubBinder = StubBinder.idle()) }
        }
        // First items are visible without scrolling
        composeTestRule.onNodeWithText("Capture").assertExists()
        composeTestRule.onNodeWithText("HDR").assertExists()
        composeTestRule.onNodeWithText("Connection").assertExists()
        // Later items require scrolling the lazy list
        composeTestRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasText("Behavior"))
        composeTestRule.onNodeWithText("Behavior").assertExists()
        composeTestRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasText("Diagnostics"))
        composeTestRule.onNodeWithText("Diagnostics").assertExists()
    }
}
