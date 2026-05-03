package eu.hyperhdr.android.probe

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ProbeScreen(context = this) }
    }
}

@Composable
private fun ProbeScreen(context: Context) {
    val orchestrator = remember { ProbeOrchestrator(context) }
    val state by orchestrator.state.collectAsState()
    var pendingProjection by remember { mutableStateOf<MediaProjection?>(null) }

    val mpManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val activity = context as ComponentActivity

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val projection = mpManager.getMediaProjection(result.resultCode, result.data!!)
            pendingProjection = projection
        }
    }

    // When projection becomes available, kick off the orchestrator.
    LaunchedEffect(pendingProjection) {
        val projection = pendingProjection ?: return@LaunchedEffect
        pendingProjection = null
        activity.lifecycleScope.launch { orchestrator.run(projection) }
    }

    Surface(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("HDR PQ Probe", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(24.dp))

            val statusText = when (val s = state) {
                ProbeOrchestrator.State.Idle -> "Idle. Press Start to grant screen capture and run probe."
                is ProbeOrchestrator.State.CapturingHdr -> "Capturing HDR (${s.frames}/60)…"
                is ProbeOrchestrator.State.CapturingSdr -> "Capturing SDR (${s.frames}/60)…"
                is ProbeOrchestrator.State.Done ->
                    "Done.\nHDR=${s.hdrFrames} SDR=${s.sdrFrames}\nFolder: ${s.outputFolder.absolutePath}"
                is ProbeOrchestrator.State.Failed -> "Failed: ${s.message}"
            }
            Text(statusText, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { launcher.launch(mpManager.createScreenCaptureIntent()) },
                enabled = state is ProbeOrchestrator.State.Idle ||
                          state is ProbeOrchestrator.State.Done ||
                          state is ProbeOrchestrator.State.Failed,
            ) {
                Text("Start")
            }

            if (state is ProbeOrchestrator.State.Done) {
                Spacer(Modifier.height(16.dp))
                val done = state as ProbeOrchestrator.State.Done
                Button(onClick = { shareFolder(context, done.outputFolder) }) {
                    Text("Share results")
                }
            }
        }
    }
}

private fun shareFolder(context: Context, folder: java.io.File) {
    // Best-effort share — opens a chooser with the folder URI. On most TV launchers there's
    // no useful share target; the primary intended retrieval path is `adb pull`.
    val uri: Uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", folder,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share probe results")) }
}
