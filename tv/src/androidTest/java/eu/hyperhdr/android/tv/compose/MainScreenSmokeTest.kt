package eu.hyperhdr.android.tv.compose

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.ExperimentalTvMaterial3Api
import eu.hyperhdr.android.tv.compose.screens.MainScreen
import eu.hyperhdr.android.tv.compose.support.StubBinder
import eu.hyperhdr.android.tv.compose.theme.HyperHdrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScreenSmokeTest {

    @get:Rule val composeTestRule = createComposeRule()

    @OptIn(ExperimentalTvMaterial3Api::class)
    @Test
    fun mainScreenIdleRendersStartCaptureAndStatsFooter() {
        val stub = StubBinder.idle()
        composeTestRule.setContent {
            HyperHdrTheme { MainScreen(stubBinder = stub) }
        }
        composeTestRule.onNodeWithText("Start capture").assertExists()
        composeTestRule.onNodeWithText("Settings").assertExists()
        composeTestRule.onNodeWithTag("stats_footer").assertExists()
    }

    @OptIn(ExperimentalTvMaterial3Api::class)
    @Test
    fun mainScreenStreamingShowsStopCapture() {
        val stub = StubBinder.streaming(fps = 30.0)
        composeTestRule.setContent {
            HyperHdrTheme { MainScreen(stubBinder = stub) }
        }
        composeTestRule.onNodeWithText("Stop capture").assertExists()
    }
}
