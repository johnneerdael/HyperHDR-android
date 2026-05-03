package eu.hyperhdr.android.tv.compose.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import eu.hyperhdr.android.service.ScreenBinder
import eu.hyperhdr.android.service.ServiceState
import eu.hyperhdr.android.settings.EncryptedProfileStorage
import eu.hyperhdr.android.settings.ProfileStore
import eu.hyperhdr.android.stats.LiveStats
import eu.hyperhdr.android.tv.BuildConfig
import eu.hyperhdr.android.tv.compose.service.rememberServiceBinder
import eu.hyperhdr.android.tv.compose.widgets.AvailableUpgrade
import eu.hyperhdr.android.tv.compose.widgets.ConnectionCard
import eu.hyperhdr.android.tv.compose.widgets.FocusableSurface
import eu.hyperhdr.android.tv.compose.widgets.StatsFooter
import eu.hyperhdr.android.tv.update.UpdateChecker
import eu.hyperhdr.android.tv.update.UpdateInstaller
import eu.hyperhdr.android.tv.ui.settings.SettingsActivity
import eu.hyperhdr.android.tv.ui.wizard.WizardActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MainScreen(stubBinder: ScreenBinder? = null) {
    val ctx = LocalContext.current
    val binder by rememberServiceBinder(stub = stubBinder)

    val state by (binder?.state ?: MutableStateFlow(ServiceState.IDLE))
        .collectAsStateWithLifecycle(ServiceState.IDLE)
    val stats by (binder?.stats ?: MutableStateFlow(LiveStats(0.0, 0L, 0, 0, null)))
        .collectAsStateWithLifecycle(LiveStats(0.0, 0L, 0, 0, null))
    val serverHdr by (binder?.serverHdrSignaled ?: MutableStateFlow(false))
        .collectAsStateWithLifecycle(false)

    val profile = remember { ProfileStore(EncryptedProfileStorage.create(ctx)).load() }

    var updateAvailable by remember { mutableStateOf<AvailableUpgrade?>(null) }
    LaunchedEffect(Unit) {
        runCatching { UpdateChecker().latest(BuildConfig.VERSION_NAME) }
            .getOrNull()?.let { updateAvailable = AvailableUpgrade(tag = it.tag, downloadUrl = it.downloadUrl) }
    }

    val installer = remember { UpdateInstaller(ctx.applicationContext) }
    val updateState by installer.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            binder?.startCapture(result.resultCode, result.data!!)
        }
    }

    val toggleFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { toggleFocus.requestFocus() }

    // Identical width for all 3 home buttons. 480.dp gives a comfortable target on
    // 1080p TV displays without filling the whole screen.
    val homeButtonWidth = Modifier.width(480.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp),
    ) {
        // Button stack — centred both horizontally and vertically in the screen.
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ConnectionCard(
                profile = profile,
                modifier = homeButtonWidth,
                onClick = { ctx.startActivity(Intent(ctx, WizardActivity::class.java)) },
            )
            Spacer(Modifier.height(16.dp))

            FocusableSurface(
                onClick = {
                    if (state == ServiceState.STREAMING || state == ServiceState.CONNECTING) {
                        binder?.stopCapture()
                    } else {
                        val pmgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        projectionLauncher.launch(pmgr.createScreenCaptureIntent())
                    }
                },
                modifier = homeButtonWidth.focusRequester(toggleFocus),
            ) {
                Text(
                    text = when (state) {
                        ServiceState.IDLE -> "Start capture"
                        ServiceState.CONNECTING -> "Connecting…"
                        ServiceState.STREAMING -> "Stop capture"
                        ServiceState.PAUSED -> "Paused (waiting for screen)"
                        ServiceState.ERROR -> "Error — tap to retry"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))

            FocusableSurface(
                onClick = { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) },
                modifier = homeButtonWidth,
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Stats + upgrade banner — pinned to the bottom edge.
        StatsFooter(
            stats = stats,
            hdrBadge = serverHdr,
            updateAvailable = updateAvailable,
            updateState = updateState,
            onUpgradeClick = {
                val upgrade = updateAvailable ?: return@StatsFooter
                val url = upgrade.downloadUrl ?: return@StatsFooter
                if (!installer.canInstall()) {
                    ctx.startActivity(installer.unknownSourcesSettingsIntent())
                    return@StatsFooter
                }
                coroutineScope.launch { installer.start(url, upgrade.tag) }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
