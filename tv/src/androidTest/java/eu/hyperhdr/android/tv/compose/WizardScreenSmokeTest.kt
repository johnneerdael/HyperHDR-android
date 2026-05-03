package eu.hyperhdr.android.tv.compose

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.ExperimentalTvMaterial3Api
import eu.hyperhdr.android.tv.compose.screens.wizard.WizardScreen
import eu.hyperhdr.android.tv.compose.theme.HyperHdrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WizardScreenSmokeTest {

    @get:Rule val composeTestRule = createComposeRule()

    @OptIn(ExperimentalTvMaterial3Api::class)
    @Test
    fun wizardScreenStartsOnDiscoveryStep() {
        composeTestRule.setContent {
            HyperHdrTheme { WizardScreen(onDone = {}) }
        }
        composeTestRule.onNodeWithText("Looking for HyperHDR servers…").assertExists()
        composeTestRule.onNodeWithText("Enter manually…").assertExists()
    }
}
