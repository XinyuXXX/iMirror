package dev.imirror.receiver.miracast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.core.content.ContextCompat
import dev.imirror.receiver.service.ProtocolState

/**
 * Coordinates the no-root Miracast sink: Wi-Fi Direct discovery, WFD RTSP,
 * RTP/MPEG-TS reception, audio playback and video decoding onto the shared Surface.
 */
class MiracastReceiver(
    context: Context,
    private val displayName: String,
    private val videoSurfaceProvider: () -> Surface?,
    private val canAcceptConnection: () -> Boolean = { true },
    private val onStateChanged: (ProtocolState) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    private var wifiDirectManager: WifiDirectManager? = null
    private var wfdServer: WfdServer? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true

        if (!hasRequiredPermissions(appContext)) {
            running = false
            onStateChanged(ProtocolState.ERROR)
            return
        }

        val p2p = WifiDirectManager(appContext, displayName).apply {
            onGroupCreated = { emitState(ProtocolState.ADVERTISING) }
            onError = { emitState(ProtocolState.ERROR) }
        }
        val server = WfdServer(videoSurfaceProvider).apply {
            onConnectionRequested = { _, _ -> running && canAcceptConnection() }
            onStreamStarted = {
                val accepted = running && canAcceptConnection()
                if (accepted) emitState(ProtocolState.CONNECTED)
                accepted
            }
            onStreamStopped = { emitState(ProtocolState.ADVERTISING) }
            onError = {
                emitState(ProtocolState.ERROR)
                disconnectSender()
            }
        }
        wifiDirectManager = p2p
        wfdServer = server

        if (p2p.start()) {
            server.start()
            emitState(ProtocolState.ADVERTISING)
        } else {
            running = false
            wifiDirectManager = null
            wfdServer = null
            onStateChanged(ProtocolState.ERROR)
        }
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { wfdServer?.stop() }
        runCatching { wifiDirectManager?.stop() }
        wfdServer = null
        wifiDirectManager = null
        onStateChanged(ProtocolState.DISABLED)
    }

    /** Ends the current RTSP/P2P session without disabling subsequent Miracast connections. */
    fun disconnectSender() {
        wfdServer?.disconnectSender()
        wifiDirectManager?.disconnectSender()
    }

    /** Used before an AirPlay decoder starts: wait for the old WFD media pipeline to release. */
    fun disconnectSenderAndWait() {
        wfdServer?.disconnectSenderAndWait()
        wifiDirectManager?.disconnectSender()
    }

    private fun emitState(state: ProtocolState) {
        if (!running) return
        mainHandler.post {
            if (running) onStateChanged(state)
        }
    }

    companion object {
        /** Runtime permissions required by WifiP2pManager on this Android version. */
        fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        fun hasRequiredPermissions(context: Context): Boolean = requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
