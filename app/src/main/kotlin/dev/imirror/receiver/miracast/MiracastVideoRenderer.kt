package dev.imirror.receiver.miracast

import android.os.SystemClock
import android.view.Surface
import dev.imirror.receiver.airplay.StreamStats
import dev.imirror.receiver.airplay.VideoDecoder
import timber.log.Timber

/**
 * Feeds Annex-B H.264 access units from MPEG-TS into iMirror's existing low-latency decoder.
 * SPS/PPS are carried in-band; a fresh decoder always waits for an IDR before decoding.
 */
class MiracastVideoRenderer(private val surfaceProvider: () -> Surface?) {

    private var decoder: VideoDecoder? = null
    private var configuredSurface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var awaitingKeyframe = true
    private var released = false
    private var droppedBeforeConfig = 0
    private var nextDecoderAttemptMs = 0L

    /** Drops reference-broken frames until the next IDR after RTP/TS loss. */
    @Synchronized
    fun onDiscontinuity() {
        if (released) return
        if (!awaitingKeyframe) {
            awaitingKeyframe = true
            Timber.w("Miracast: data loss detected, waiting for next keyframe")
        }
    }

    @Synchronized
    fun onAccessUnit(unit: ByteArray, ptsUs: Long) {
        if (released) return
        if (captureParameterSets(unit)) {
            releaseDecoder()
            awaitingKeyframe = true
        }

        val current = decoder
        if (current != null && !current.isHealthy) {
            Timber.w("Miracast: decoder unhealthy, scheduling bounded recreation")
            releaseDecoder()
            awaitingKeyframe = true
        }

        val surface = surfaceProvider()
        if (surface == null || !surface.isValid) {
            releaseDecoder()
            return
        }

        // SurfaceView creates and destroys Surface objects asynchronously. Never retain a dead one.
        if (surface !== configuredSurface) {
            releaseDecoder()
            awaitingKeyframe = true
        }
        if (decoder == null && !rebuildDecoder(surface)) {
            droppedBeforeConfig++
            if (droppedBeforeConfig % 60 == 0) {
                Timber.d("Miracast: waiting for decoder/SPS/PPS, dropped $droppedBeforeConfig units")
            }
            return
        }

        if (awaitingKeyframe) {
            if (!containsIdr(unit)) return
            awaitingKeyframe = false
            Timber.i("Miracast: keyframe found, decoding started")
        }
        decoder?.decodeNalUnit(unit, ptsUs)
    }

    /** Creation attempts (including failed/unhealthy decoders) are limited to one per second. */
    private fun rebuildDecoder(surface: Surface): Boolean {
        if (released) return false
        val spsBytes = sps ?: return false
        val ppsBytes = pps ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now < nextDecoderAttemptMs) return false
        nextDecoderAttemptMs = now + DECODER_RETRY_INTERVAL_MS

        releaseDecoder()
        val (width, height) = VideoDecoder.parseSpsResolution(withoutStartCode(spsBytes))
            ?: (1920 to 1080)
        val newDecoder = VideoDecoder(surface)
        return try {
            newDecoder.initialize(spsBytes, ppsBytes, width, height)
            decoder = newDecoder
            configuredSurface = surface
            awaitingKeyframe = true
            StreamStats.videoRes = "${width}x${height}"
            Timber.i("Miracast decoder initialized: ${width}x$height")
            true
        } catch (e: Exception) {
            Timber.e(e, "Miracast: decoder initialization failed")
            newDecoder.release()
            decoder = null
            configuredSurface = null
            false
        }
    }

    /** Captures SPS/PPS with Annex-B start codes and detects in-band resolution/config changes. */
    private fun captureParameterSets(unit: ByteArray): Boolean {
        var changed = false
        forEachNal(unit) { start, end ->
            val nalType = unit[start].toInt() and 0x1F
            if (nalType == 7 || nalType == 8) {
                val withStartCode = ByteArray(4 + end - start)
                withStartCode[3] = 1
                System.arraycopy(unit, start, withStartCode, 4, end - start)
                if (nalType == 7 && sps?.contentEquals(withStartCode) != true) {
                    sps = withStartCode
                    changed = true
                    Timber.i("Miracast: SPS captured (${end - start} bytes)")
                } else if (nalType == 8 && pps?.contentEquals(withStartCode) != true) {
                    pps = withStartCode
                    changed = true
                    Timber.i("Miracast: PPS captured (${end - start} bytes)")
                }
            }
        }
        return changed
    }

    private fun containsIdr(unit: ByteArray): Boolean {
        var found = false
        forEachNal(unit) { start, _ ->
            if ((unit[start].toInt() and 0x1F) == 5) found = true
        }
        return found
    }

    /** Iterates NAL payloads, excluding their 3- or 4-byte Annex-B start codes. */
    private inline fun forEachNal(data: ByteArray, action: (start: Int, end: Int) -> Unit) {
        var i = 0
        var nalStart = -1
        while (i + 3 <= data.size) {
            val isStartCode3 = data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 1
            val isStartCode4 = i + 4 <= data.size && data[i].toInt() == 0 &&
                data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1
            if (isStartCode3 || isStartCode4) {
                if (nalStart >= 0) action(nalStart, i)
                i += if (isStartCode4) 4 else 3
                nalStart = i
            } else {
                i++
            }
        }
        if (nalStart in 0 until data.size) action(nalStart, data.size)
    }

    private fun withoutStartCode(data: ByteArray): ByteArray = when {
        data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            data[2] == 0.toByte() && data[3] == 1.toByte() -> data.copyOfRange(4, data.size)
        data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            data[2] == 1.toByte() -> data.copyOfRange(3, data.size)
        else -> data
    }

    private fun releaseDecoder() {
        decoder?.release()
        decoder = null
        configuredSurface = null
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        releaseDecoder()
        sps = null
        pps = null
        awaitingKeyframe = true
        nextDecoderAttemptMs = 0L
    }

    companion object {
        private const val DECODER_RETRY_INTERVAL_MS = 1_000L
    }
}
