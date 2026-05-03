package eu.hyperhdr.android.tv.ui.wizard

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import eu.hyperhdr.android.tv.compose.screens.wizard.WizardScreen
import eu.hyperhdr.android.tv.compose.theme.HyperHdrTheme

class WizardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HyperHdrTheme {
                WizardScreen(onDone = {
                    setResult(Activity.RESULT_OK)
                    finish()
                })
            }
        }
    }
}
