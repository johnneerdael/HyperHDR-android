package eu.hyperhdr.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import eu.hyperhdr.android.capture.CaptureConfig
import eu.hyperhdr.android.capture.CaptureTier
import eu.hyperhdr.android.capture.CpuRgbFallbackEncoder
import eu.hyperhdr.android.capture.Egl16BitCapabilityProbe
import eu.hyperhdr.android.capture.HyperHdrGpuEncoder
import eu.hyperhdr.android.flatbuf.ConnectionState
import eu.hyperhdr.android.flatbuf.HyperHdrFlatBufferReconnector
import eu.hyperhdr.android.json.HyperHdrJsonApiClient
import eu.hyperhdr.android.settings.EncryptedProfileStorage
import eu.hyperhdr.android.settings.ProfileStore
import eu.hyperhdr.android.settings.ServerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class HyperHdrCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "hyperhdr_capture"
        const val NOTIF_ID = 1
        const val ACTION_TOGGLE = "eu.hyperhdr.android.action.TOGGLE"
    }

    private val sm = ServiceStateMachine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var profileStore: ProfileStore

    private var encoder: HyperHdrGpuEncoder? = null
    private var cpuFallback: CpuRgbFallbackEncoder? = null
    private var reconnector: HyperHdrFlatBufferReconnector? = null
    private var jsonClient: HyperHdrJsonApiClient? = null
    private var stateCollector: Job? = null
    private var hdrDetector: eu.hyperhdr.android.hdr.HdrDetector? = null
    private var hdrCollectorJob: Job? = null
    val statsCollector = eu.hyperhdr.android.stats.LiveStatsCollector()
    private var sampleJob: Job? = null

    var jsonEvents: eu.hyperhdr.android.json.HyperHdrJsonApiEvents? = null
        private set
    private var jsonEventsCollectorJob: Job? = null
    private val _serverHdrSignaled = kotlinx.coroutines.flow.MutableStateFlow(false)
    val serverHdrSignaled: kotlinx.coroutines.flow.StateFlow<Boolean> = _serverHdrSignaled

    val stateFlow get() = sm.flow
    fun lastError(): String? = sm.lastErrorMessage

    /** Plan 4 hooks HDR detection here. Currently a stub that returns success when JSON client is up. */
    suspend fun setHdrVideoMode(hdr: Boolean): Boolean = try {
        jsonClient?.setHdrVideoMode(hdr)
        true
    } catch (_: Exception) { false }

    override fun onCreate() {
        super.onCreate()
        profileStore = ProfileStore(EncryptedProfileStorage.create(this))
        hdrDetector = eu.hyperhdr.android.hdr.HdrDetector(this).also { it.start() }
        ensureChannel()
        val notif = buildNotification(ServiceState.IDLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, foregroundType())
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onBind(intent: Intent?): IBinder = HyperHdrServiceBinder(this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) {
            if (sm.state == ServiceState.STREAMING || sm.state == ServiceState.CONNECTING) stopCapture()
            // For toggle-on, the UI/tile path must invoke startCapture with a fresh projection result.
        }
        return START_STICKY
    }

    fun startCapture(projectionResultCode: Int, projectionData: Intent) {
        val profile = profileStore.load() ?: run {
            sm.onError("No server configured. Open the app to set up.")
            return
        }
        sm.onToggleOn()
        updateNotif()

        val pmgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection: MediaProjection = pmgr.getMediaProjection(projectionResultCode, projectionData)

        val r = HyperHdrFlatBufferReconnector(
            host = profile.host, port = profile.flatbufPort, priority = profile.priority,
            statsCollector = statsCollector,
        ).also { it.start() }
        reconnector = r
        sampleJob = scope.launch {
            while (true) { kotlinx.coroutines.delay(1000); statsCollector.sample() }
        }

        stateCollector = scope.launch {
            r.state.collect { cs ->
                when (cs) {
                    ConnectionState.CONNECTED -> { sm.onConnected(); updateNotif() }
                    ConnectionState.ERROR -> { sm.onError("Network error"); updateNotif() }
                    else -> { /* CONNECTING/DISCONNECTED handled via sm transitions */ }
                }
            }
        }

        jsonClient = HyperHdrJsonApiClient(
            host = profile.host, port = profile.jsonPort,
        ).also { client ->
            scope.launch {
                // Authorize + switch + probe capabilities up front. supportsP010
                // gates the HDR_NATIVE tier below; absence (stock HyperHDR) means
                // we must NOT send P010 frames or the server will drop them.
                val supportsP010 = try {
                    profile.token?.let { client.authorize(it) }
                    client.switchInstance(profile.instanceId)
                    client.serverInfo().supportsP010
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    sm.onError("JSON-API: ${t.message}")
                    false
                }

                buildEncoderAndStart(profile, projection, r, supportsP010)
            }
        }

        jsonEvents = eu.hyperhdr.android.json.HyperHdrJsonApiEvents(
            host = profile.host, port = profile.jsonPort,
        ).also { it.start(profile.token) }

        jsonEventsCollectorJob = scope.launch {
            jsonEvents?.flow?.collect { ev ->
                when (ev) {
                    is eu.hyperhdr.android.json.JsonEvent.VideoModeChanged ->
                        _serverHdrSignaled.value = ev.hdr
                    is eu.hyperhdr.android.json.JsonEvent.ComponentChanged -> {
                        if (ev.name == "HDR") _serverHdrSignaled.value = ev.enabled
                    }
                    else -> { /* InstanceChanged, ServerInfoSnapshot — UI surfaces consume directly */ }
                }
            }
        }

        val detector = hdrDetector
        val client = jsonClient
        if (detector != null && client != null) {
            hdrCollectorJob = scope.launch {
                detector.current.collect { type ->
                    runCatching { client.setHdrVideoMode(type != eu.hyperhdr.android.hdr.HdrType.SDR) }
                        // JSON-API failures don't break capture; spec §8.1.
                }
            }
        }
    }

    fun stopCapture() {
        cpuFallback?.stop(); cpuFallback = null
        encoder?.stop(); encoder = null
        reconnector?.close(); reconnector = null
        jsonClient = null
        stateCollector?.cancel(); stateCollector = null
        hdrCollectorJob?.cancel(); hdrCollectorJob = null
        sampleJob?.cancel(); sampleJob = null
        jsonEventsCollectorJob?.cancel(); jsonEventsCollectorJob = null
        jsonEvents?.close(); jsonEvents = null
        _serverHdrSignaled.value = false
        sm.onToggleOff()
        updateNotif()
    }

    override fun onDestroy() {
        stopCapture()
        hdrDetector?.stop(); hdrDetector = null
        hdrCollectorJob?.cancel(); hdrCollectorJob = null
        scope.cancel()
        super.onDestroy()
    }

    private fun buildEncoderAndStart(
        profile: ServerProfile,
        projection: MediaProjection,
        sink: HyperHdrFlatBufferReconnector,
        serverSupportsP010: Boolean,
    ) {
        val dm = DisplayMetrics().also {
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it)
        }
        // Tier selection: HDR_NATIVE requires (a) user opted in, (b) device capability probe
        // passed, (c) hdrAware is also on, AND (d) the HyperHDR server advertises P010 via
        // serverinfo.info.flatbuffer.imageFormats (read by ServerInfo.supportsP010). Stock
        // HyperHDR fails (d) and falls back.
        val tier = when {
            profile.hdrNative && profile.hdrAware && serverSupportsP010 &&
                Egl16BitCapabilityProbe.supportsR16UiFboStandalone() ->
                    CaptureTier.HDR_NATIVE
            profile.hdrAware -> CaptureTier.HDR_AWARE
            else -> CaptureTier.SDR
        }
        val baseSize = if (profile.highQuality) CaptureConfig.HIGH else CaptureConfig.STANDARD
        val cfg = baseSize.copy(tier = tier)
        encoder = try {
            HyperHdrGpuEncoder(
                mediaProjection = projection,
                sourceWidth = dm.widthPixels,
                sourceHeight = dm.heightPixels,
                density = dm.densityDpi,
                config = cfg,
                sink = sink,
                onProjectionStopped = {
                    scope.launch { sm.onProjectionPaused(); updateNotif() }
                },
            ).also { it.start() }
        } catch (t: Throwable) {
            sm.onError("GPU pipeline unavailable, using CPU fallback")
            updateNotif()
            null
        }
        if (encoder == null) {
            cpuFallback = CpuRgbFallbackEncoder(
                mediaProjection = projection,
                sourceWidth = dm.widthPixels,
                sourceHeight = dm.heightPixels,
                density = dm.densityDpi,
                config = cfg,
                sink = sink,
            ).also { it.start() }
        }
    }

    // --- helpers ---

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "HyperHDR capture", NotificationManager.IMPORTANCE_LOW,
            ))
        }
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else 0

    private fun updateNotif() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(sm.state))
    }

    private fun buildNotification(state: ServiceState): Notification {
        val toggle = PendingIntent.getService(
            this, 0,
            Intent(this, HyperHdrCaptureService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (state) {
            ServiceState.IDLE -> "Idle — open the app to start capture"
            ServiceState.CONNECTING -> "Connecting to HyperHDR…"
            ServiceState.STREAMING -> "Streaming to HyperHDR"
            ServiceState.PAUSED -> "Paused"
            ServiceState.ERROR -> "Error: ${sm.lastErrorMessage ?: "unknown"}"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HyperHDR")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", toggle)
            .build()
    }
}
