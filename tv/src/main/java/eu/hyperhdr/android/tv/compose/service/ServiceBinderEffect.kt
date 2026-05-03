package eu.hyperhdr.android.tv.compose.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.hyperhdr.android.service.HyperHdrCaptureService
import eu.hyperhdr.android.service.ScreenBinder

/**
 * Composition-scoped service binding. Binds in the composition's lifecycle, unbinds when
 * the composition leaves. Returned [State] is null while the connection is pending;
 * consumers should default-state-flow the case.
 */
@Composable
fun rememberServiceBinder(): State<ScreenBinder?> {
    val ctx = LocalContext.current
    val binderState = remember { mutableStateOf<ScreenBinder?>(null) }

    DisposableEffect(Unit) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binderState.value = service as? ScreenBinder
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                binderState.value = null
            }
        }
        ctx.startForegroundService(Intent(ctx, HyperHdrCaptureService::class.java))
        ctx.bindService(
            Intent(ctx, HyperHdrCaptureService::class.java),
            conn, Context.BIND_AUTO_CREATE,
        )
        onDispose {
            runCatching { ctx.unbindService(conn) }
            binderState.value = null
        }
    }

    return binderState
}
