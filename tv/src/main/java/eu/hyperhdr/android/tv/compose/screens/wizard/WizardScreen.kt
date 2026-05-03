package eu.hyperhdr.android.tv.compose.screens.wizard

import android.content.Context
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.hyperhdr.android.discovery.HyperHdrMdnsDiscovery
import eu.hyperhdr.android.settings.EncryptedProfileStorage
import eu.hyperhdr.android.settings.ProfileStore
import eu.hyperhdr.android.settings.ServerProfile

@Composable
fun WizardScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var step: WizardStep by remember { mutableStateOf(WizardStep.Discovery) }

    val discovery = remember {
        HyperHdrMdnsDiscovery(
            ctx.getSystemService(Context.NSD_SERVICE) as NsdManager,
            ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager,
        )
    }
    DisposableEffect(Unit) {
        discovery.start()
        onDispose { discovery.stop() }
    }

    BackHandler(enabled = step !is WizardStep.Discovery) {
        step = when (val s = step) {
            is WizardStep.Manual -> WizardStep.Discovery
            is WizardStep.Auth -> WizardStep.Discovery
            is WizardStep.Instance -> WizardStep.Auth(s.draft)
            else -> s
        }
    }

    when (val current = step) {
        is WizardStep.Discovery -> DiscoveryStep(
            discovery = discovery,
            onPick = { server ->
                step = WizardStep.Auth(
                    ServerProfile(
                        host = server.host,
                        flatbufPort = server.flatbufPort,
                        jsonPort = server.jsonPort,
                    )
                )
            },
            onManual = { step = WizardStep.Manual },
        )
        is WizardStep.Manual -> ManualStep(
            onNext = { draft -> step = WizardStep.Auth(draft) },
        )
        is WizardStep.Auth -> AuthStep(
            draft = current.draft,
            onNext = { withToken -> step = WizardStep.Instance(withToken) },
        )
        is WizardStep.Instance -> InstanceStep(
            draft = current.draft,
            onPick = { saved ->
                ProfileStore(EncryptedProfileStorage.create(ctx)).save(saved)
                onDone()
            },
        )
    }
}
