package eu.hyperhdr.android.tv.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.leanback.preference.LeanbackPreferenceFragment
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import eu.hyperhdr.android.debug.DebugBundleExporter
import eu.hyperhdr.android.hdr.HdrDetector
import eu.hyperhdr.android.settings.EncryptedProfileStorage
import eu.hyperhdr.android.settings.ProfileStore
import eu.hyperhdr.android.tv.ui.wizard.WizardActivity

class SettingsFragment : LeanbackPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = preferenceManager.context
        val store = ProfileStore(EncryptedProfileStorage.create(ctx))
        var profile = store.load()
        val screen = preferenceManager.createPreferenceScreen(ctx)

        // -------- Capture --------
        val capture = PreferenceCategory(ctx).apply { title = "Capture" }
        screen.addPreference(capture)

        val highQuality = SwitchPreferenceCompat(ctx).apply {
            key = "high_quality"; title = "High quality (192×108 @ 60 fps)"
            isChecked = profile?.highQuality == true
            setOnPreferenceChangeListener { _, v ->
                profile?.let { store.save(it.copy(highQuality = v as Boolean)); profile = store.load() }
                true
            }
        }
        capture.addPreference(highQuality)

        val priority = EditTextPreference(ctx).apply {
            key = "priority"; title = "Priority"
            summary = (profile?.priority ?: 100).toString()
            text = (profile?.priority ?: 100).toString()
            setOnPreferenceChangeListener { _, v ->
                val p = (v as String).toIntOrNull() ?: return@setOnPreferenceChangeListener false
                profile?.let { store.save(it.copy(priority = p)); profile = store.load() }
                summary = p.toString(); true
            }
        }
        capture.addPreference(priority)

        // -------- HDR --------
        val hdrCat = PreferenceCategory(ctx).apply { title = "HDR" }
        screen.addPreference(hdrCat)

        val detector = HdrDetector(ctx).also { it.start() }
        val capable = detector.capable.value
        detector.stop() // we only need a one-shot read here

        val hdrAware = SwitchPreferenceCompat(ctx).apply {
            key = "hdr_aware"; title = "HDR-aware capture"
            summary = if (capable) "Tone-map HDR content on the device" else "Display does not report HDR support"
            isEnabled = capable
            isChecked = profile?.hdrAware == true
            setOnPreferenceChangeListener { _, v ->
                profile?.let { store.save(it.copy(hdrAware = v as Boolean)); profile = store.load() }
                true
            }
        }
        hdrCat.addPreference(hdrAware)

        val signalHdr = SwitchPreferenceCompat(ctx).apply {
            key = "signal_hdr"; title = "Signal HDR to HyperHDR"
            summary = "Tells HyperHDR to apply its tone-map LUT when content is HDR"
            isChecked = true
        }
        hdrCat.addPreference(signalHdr)

        // -------- Connection --------
        val conn = PreferenceCategory(ctx).apply { title = "Connection" }
        screen.addPreference(conn)

        val reconfigure = Preference(ctx).apply {
            title = "Reconfigure server…"
            summary = profile?.let { "${it.host}:${it.flatbufPort}" } ?: "Not configured"
            setOnPreferenceClickListener {
                startActivity(Intent(ctx, WizardActivity::class.java)); true
            }
        }
        conn.addPreference(reconfigure)

        val token = EditTextPreference(ctx).apply {
            key = "token"; title = "Auth token"
            summary = if (profile?.token != null) "Set" else "Not set"
            text = profile?.token.orEmpty()
            setOnPreferenceChangeListener { _, v ->
                profile?.let {
                    val newTok = (v as String).takeIf { s -> s.isNotBlank() }
                    store.save(it.copy(token = newTok)); profile = store.load()
                    summary = if (newTok != null) "Set" else "Not set"
                }; true
            }
        }
        conn.addPreference(token)

        // -------- Behavior --------
        val behavior = PreferenceCategory(ctx).apply { title = "Behavior" }
        screen.addPreference(behavior)

        val startOnBoot = SwitchPreferenceCompat(ctx).apply {
            key = "start_on_boot"; title = "Start on boot"
            summary = "Foreground service starts at boot (capture still requires user consent)"
            isChecked = true
        }
        behavior.addPreference(startOnBoot)

        // -------- Diagnostics --------
        val diag = PreferenceCategory(ctx).apply { title = "Diagnostics" }
        screen.addPreference(diag)

        val export = Preference(ctx).apply {
            title = "Export diagnostic bundle"
            summary = "Saves a zip of recent logs to the device's Downloads folder"
            setOnPreferenceClickListener {
                val path = DebugBundleExporter.export(ctx)
                summary = "Saved: $path"
                true
            }
        }
        diag.addPreference(export)

        preferenceScreen = screen
    }
}
