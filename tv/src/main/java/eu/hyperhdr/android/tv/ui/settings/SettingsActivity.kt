package eu.hyperhdr.android.tv.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import eu.hyperhdr.android.tv.compose.screens.SettingsScreen
import eu.hyperhdr.android.tv.compose.theme.HyperHdrTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HyperHdrTheme { SettingsScreen() }
        }
    }
}
