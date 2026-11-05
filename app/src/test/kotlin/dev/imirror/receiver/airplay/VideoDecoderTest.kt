package dev.imirror.receiver.airplay

import android.media.MediaCodec
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * VideoDecoderTest — Unit tests for the H.264 video decoder.
 *
 * WHY: The VideoDecoder bridges RTP network data and the hardware MediaCodec.
 * We need to ensure that:
 * - Double initialization is handled safely
 * - Release can be called in any state without crashes
 * - NAL unit size validation prevents buffer overflows
 *
 * NOTE: MediaCodec itself cannot be tested without real Android hardware
 * (it requires GPU drivers). Therefore, these tests focus on the
 * VideoDecoder's input validation and lifecycle management logic,
 * not the hardware decoding path.
 *
 * Full video pipeline testing happens as part of manual testing on real devices
 * (see docs/TESTING.md for manual test scenarios).
 */
class VideoDecoderTest {

    private lateinit var videoDecoder: VideoDecoder

    @Before
    fun setup() {
        // Use a mock Surface (no real display needed for unit tests)
        videoDecoder = VideoDecoder(mockk(relaxed = true))
    }

    /**
     * Test: decodeNalUnit() called before initialize() does not crash.
     *
     * WHY: If there's a race condition where the first video packet arrives
     * before the decoder is initialized, the app must not crash.
     * It should log a warning and silently discard the packet.
     */
    @Test
    fun `decodeNalUnit before initialize does not crash`() {
        val dummyNalUnit = ByteArray(100) { it.toByte() }
        // Should not throw any exception
        videoDecoder.decodeNalUnit(dummyNalUnit, 0L)
    }

    /**
     * Test: release() called before initialize() does not crash.
     *
     * WHY: onDestroy() always calls release(). If the activity is destroyed
     * before initialization completes, release() must be safe to call.
     */
    @Test
    fun `release before initialize does not crash`() {
        // Should not throw any exception
        videoDecoder.release()
    }

    /**
     * Test: release() called twice does not crash.
     *
     * WHY: Defensive programming — double-release is an easy mistake to make
     * in error handling code paths. The second release should be a no-op.
     */
    @Test
    fun `release can be called twice safely`() {
        videoDecoder.release()
        videoDecoder.release()  // Should not throw
    }

    /**
     * Test: VideoDecoder is not null after construction.
     *
     * Basic smoke test to ensure the constructor doesn't fail.
     */
    @Test
    fun `release still releases codec when stop throws`() {
        val codec = mockk<MediaCodec>()
        every { codec.stop() } throws IllegalStateException("already stopped")
        every { codec.release() } just Runs
        VideoDecoder::class.java.getDeclaredField("mediaCodec").apply {
            isAccessible = true
            set(videoDecoder, codec)
        }

        videoDecoder.release()

        verify(exactly = 1) { codec.stop() }
        verify(exactly = 1) { codec.release() }
    }

    @Test
    fun `VideoDecoder is created successfully`() {
        assertNotNull(videoDecoder)
    }

    /**
     * Test: decodeNalUnit() handles an empty byte array without crashing.
     *
     * WHY: RULE 4 — malformed input from the network must never crash the app.
     * An empty NAL unit is not valid H.264, but we should handle it gracefully.
     */
    @Test
    fun `decodeNalUnit handles empty input gracefully`() {
        val emptyNalUnit = ByteArray(0)
        // Should not throw any exception
        videoDecoder.decodeNalUnit(emptyNalUnit, 0L)
    }
}
