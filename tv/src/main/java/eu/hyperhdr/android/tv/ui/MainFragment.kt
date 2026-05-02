package eu.hyperhdr.android.tv.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import eu.hyperhdr.android.service.HyperHdrCaptureService
import eu.hyperhdr.android.service.HyperHdrServiceBinder
import eu.hyperhdr.android.service.ServiceState
import eu.hyperhdr.android.settings.EncryptedProfileStorage
import eu.hyperhdr.android.settings.ProfileStore
import eu.hyperhdr.android.tv.R
import eu.hyperhdr.android.tv.ui.settings.SettingsActivity
import eu.hyperhdr.android.tv.ui.wizard.WizardActivity
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainFragment : Fragment() {

    private val REQ_PROJECTION = 1
    private var binder: HyperHdrServiceBinder? = null
    private var pendingProjection: Intent? = null

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as HyperHdrServiceBinder
            observeState()
            // If a projection result was pending while we waited for the binder, deliver now.
            pendingProjection?.let { intent ->
                binder?.startCapture(Activity.RESULT_OK, intent)
                pendingProjection = null
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_main, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val profileStore = ProfileStore(EncryptedProfileStorage.create(requireContext()))
        val server = view.findViewById<Button>(R.id.btn_server)
        val toggle = view.findViewById<Button>(R.id.btn_toggle)
        val stats = view.findViewById<TextView>(R.id.tv_stats)
        val settings = view.findViewById<Button>(R.id.btn_settings)

        val profile = profileStore.load()
        server.text = profile?.let { "${it.host} (instance ${it.instanceId})" } ?: "Configure server…"
        server.setOnClickListener {
            startActivity(Intent(requireContext(), WizardActivity::class.java))
        }
        toggle.setOnClickListener {
            if (binder?.state?.value == ServiceState.STREAMING || binder?.state?.value == ServiceState.CONNECTING) {
                binder?.stopCapture()
            } else {
                val pmgr = requireActivity().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(pmgr.createScreenCaptureIntent(), REQ_PROJECTION)
            }
        }
        settings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        stats.text = "" // Plan 4 hooks live stats here.
    }

    override fun onStart() {
        super.onStart()
        val ctx = requireContext()
        ctx.startForegroundService(Intent(ctx, HyperHdrCaptureService::class.java))
        ctx.bindService(
            Intent(ctx, HyperHdrCaptureService::class.java),
            serviceConn, Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        runCatching { requireContext().unbindService(serviceConn) }
        binder = null
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != Activity.RESULT_OK || data == null) return
        val b = binder
        if (b != null) b.startCapture(resultCode, data) else pendingProjection = data
    }

    private fun observeState() {
        val toggle = view?.findViewById<Button>(R.id.btn_toggle) ?: return
        val statsView = view?.findViewById<TextView>(R.id.tv_stats) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            binder?.state?.collect { s ->
                toggle.text = when (s) {
                    ServiceState.IDLE -> "Start capture"
                    ServiceState.CONNECTING -> "Connecting…"
                    ServiceState.STREAMING -> "Stop capture"
                    ServiceState.PAUSED -> "Paused (waiting for screen)"
                    ServiceState.ERROR -> "Error — tap to retry"
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            binder?.stats?.collect { s ->
                statsView.text = if (s.width == 0) "" else String.format(
                    java.util.Locale.US,
                    "%.1f fps · %d KB/s · %dx%d",
                    s.fps, s.bytesPerSec / 1024, s.width, s.height,
                ) + (s.lastErrorMessage?.let { "  ⚠  $it" } ?: "")
            }
        }
    }
}
