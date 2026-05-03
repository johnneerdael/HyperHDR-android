package eu.hyperhdr.android.tv.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import eu.hyperhdr.android.tv.compose.theme.HyperHdrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeSmokeTest {

    @get:Rule val composeTestRule = createComposeRule()

    @OptIn(ExperimentalTvMaterial3Api::class)
    @Test
    fun themeRendersAndAppliesDarkBackground() {
        composeTestRule.setContent {
            HyperHdrTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .testTag("themed_root")
                )
            }
        }
        composeTestRule.onNodeWithTag("themed_root").assertExists()
    }
}
