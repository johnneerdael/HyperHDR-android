package eu.hyperhdr.android.hdr

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HdrDetector(context: Context) {

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val thread = HandlerThread("HyperHdrHdrDetect").also { it.start() }
    private val handler = Handler(thread.looper)

    private val _capable = MutableStateFlow(detectCapability())
    val capable: StateFlow<Boolean> = _capable.asStateFlow()

    private val _current = MutableStateFlow(HdrType.SDR)
    val current: StateFlow<HdrType> = _current.asStateFlow()

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    fun start() {
        displayManager.registerDisplayListener(listener, handler)
        refresh()
    }

    fun stop() {
        runCatching { displayManager.unregisterDisplayListener(listener) }
        thread.quitSafely()
    }

    private fun detectCapability(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        val caps = display.hdrCapabilities ?: return false
        return caps.supportedHdrTypes.isNotEmpty()
    }

    private fun refresh() {
        _capable.value = detectCapability()
        _current.value = readCurrentType()
    }

    private fun readCurrentType(): HdrType {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return HdrType.SDR
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return HdrType.SDR
        // Display.isHdr() is API 24+. It tells us if the *current frame source* is reporting HDR.
        @Suppress("NewApi")
        return if (display.isHdr) {
            // We can't precisely identify HDR10 vs HLG vs DV from public API; report HDR10 as the
            // common case. Plan 4 only needs a binary HDR/SDR signal; the precise type is for the UI.
            HdrType.HDR10
        } else HdrType.SDR
    }
}
