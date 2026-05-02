package eu.hyperhdr.android.tv.ui.settings

import android.os.Bundle
import androidx.leanback.preference.LeanbackPreferenceFragment
import androidx.preference.SwitchPreferenceCompat
import eu.hyperhdr.android.settings.EncryptedProfileStorage
import eu.hyperhdr.android.settings.ProfileStore

@Suppress("DEPRECATION")
class SettingsFragment : LeanbackPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(ctx)
        val store = ProfileStore(EncryptedProfileStorage.create(ctx))
        val profile = store.load() ?: return run { preferenceScreen = screen }

        val highQuality = SwitchPreferenceCompat(ctx).apply {
            key = "high_quality"
            title = "High quality (192×108 @ 60 fps)"
            isChecked = profile.highQuality
            setOnPreferenceChangeListener { _, newValue ->
                store.save(profile.copy(highQuality = newValue as Boolean))
                true
            }
        }
        screen.addPreference(highQuality)

        preferenceScreen = screen
    }
}
