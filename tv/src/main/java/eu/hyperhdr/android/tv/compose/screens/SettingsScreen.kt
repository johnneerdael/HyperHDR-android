package eu.hyperhdr.android.tv.compose.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import eu.hyperhdr.android.debug.DebugBundleExporter
import eu.hyperhdr.android.hdr.HdrDetector
import eu.hyperhdr.android.json.JsonEvent
import eu.hyperhdr.android.service.ScreenBinder
import eu.hyperhdr.android.settings.EncryptedProfileStorage
import eu.hyperhdr.android.settings.ProfileStore
import eu.hyperhdr.android.tv.compose.service.rememberServiceBinder
import eu.hyperhdr.android.tv.compose.widgets.CategoryHeader
import eu.hyperhdr.android.tv.compose.widgets.ClickablePreference
import eu.hyperhdr.android.tv.compose.widgets.SwitchPreference
import eu.hyperhdr.android.tv.ui.wizard.WizardActivity

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(stubBinder: ScreenBinder? = null) {
    val ctx = LocalContext.current
    val store = remember { ProfileStore(EncryptedProfileStorage.create(ctx)) }
    var profile by remember { mutableStateOf(store.load()) }
    var diagSummary by remember { mutableStateOf("Saves a zip of recent logs to the device's Downloads folder") }
    var connectionSummary by remember(profile) {
        mutableStateOf(profile?.let { "${it.host}:${it.flatbufPort}" } ?: "Not configured")
    }

    val hdrCapable = remember {
        // One-shot read; the live detector lives in the service.
        val detector = HdrDetector(ctx).also { it.start() }
        val capable = detector.capable.value
        detector.stop()
        capable
    }

    // Live updates: subscribe to InstanceChanged via the binder if available.
    val rememberedBinder by rememberServiceBinder(stub = stubBinder)
    val binder = stubBinder ?: rememberedBinder
    LaunchedEffect(binder) {
        binder?.jsonEventsFlow?.collect { ev ->
            if (ev is JsonEvent.InstanceChanged) {
                val current = ev.instances.firstOrNull { it.id == profile?.instanceId }
                connectionSummary = current?.let { "${profile?.host} → ${it.name}" }
                    ?: "${profile?.host}:${profile?.flatbufPort}"
            }
        }
    }

    TvLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 64.dp, vertical = 32.dp)
            .testTag("settings_list"),
    ) {
        item { CategoryHeader("Capture") }
        item {
            SwitchPreference(
                title = "High quality (192×108 @ 60 fps)",
                checked = profile?.highQuality == true,
                onChange = { v ->
                    profile?.let { store.save(it.copy(highQuality = v)); profile = store.load() }
                },
            )
        }
        item {
            ClickablePreference(
                title = "Priority",
                summary = (profile?.priority ?: 100).toString(),
                onClick = { /* v1.0.0 stub: editable in next polish — defer to v1.1 */ },
            )
        }

        item { CategoryHeader("HDR") }
        item {
            SwitchPreference(
                title = "HDR-aware capture",
                summary = if (hdrCapable) "Tone-map HDR content on the device"
                          else "Display does not report HDR support",
                enabled = hdrCapable,
                checked = profile?.hdrAware == true,
                onChange = { v ->
                    profile?.let { store.save(it.copy(hdrAware = v)); profile = store.load() }
                },
            )
        }
        item {
            // Probe runs once per Compose lifecycle; results are cached.
            val p010Capable = remember { eu.hyperhdr.android.capture.Egl16BitCapabilityProbe.supportsR16UiFboStandalone() }
            val canEnable = (profile?.hdrAware == true) && p010Capable
            val summary = when {
                !p010Capable -> "Your display driver doesn't support 10-bit render targets"
                profile?.hdrAware != true -> "Enable HDR-aware capture above first"
                else -> "EXPERIMENTAL — requires HyperHDR with the P010 patch (johnneerdael/HyperHDR-10bit fork or merged upstream)"
            }
            SwitchPreference(
                title = "10-bit HDR transport (experimental)",
                summary = summary,
                enabled = canEnable,
                checked = profile?.hdrNative == true,
                onChange = { v ->
                    profile?.let { store.save(it.copy(hdrNative = v)); profile = store.load() }
                },
            )
        }

        item { CategoryHeader("Connection") }
        item {
            ClickablePreference(
                title = "Reconfigure server…",
                summary = connectionSummary,
                onClick = { ctx.startActivity(Intent(ctx, WizardActivity::class.java)) },
            )
        }
        item {
            ClickablePreference(
                title = "Auth token",
                summary = if (profile?.token != null) "Set" else "Not set",
                onClick = { /* v1.0.0 stub: token edit deferred — wizard re-paste path covers this */ },
            )
        }

        item { CategoryHeader("Diagnostics") }
        item {
            ClickablePreference(
                title = "Export diagnostic bundle",
                summary = diagSummary,
                onClick = {
                    val path = DebugBundleExporter.export(ctx)
                    diagSummary = "Saved: $path"
                },
            )
        }
    }
}
