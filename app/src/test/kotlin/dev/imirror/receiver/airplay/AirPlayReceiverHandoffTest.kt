package dev.imirror.receiver.airplay

import android.content.Context
import android.net.nsd.NsdManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

class AirPlayReceiverHandoffTest {

    @Test
    fun `disconnectActiveSender waits for existing cleanup and releases old decoder`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        lateinit var receiver: AirPlayReceiver
        val stopCallback = AirPlayReceiver::class.java.getDeclaredMethod("onStreamingStopped")
            .apply { isAccessible = true }
        val handler = object : RtspHandler(
            context = mockk(relaxed = true),
            videoSurfaceProvider = { null },
            onStreamingStarted = {},
            onStreamingStopped = {}
        ) {
            override fun closeCurrentSession(): Boolean {
                // Simulate handleClient's finally callback after the sender socket closes.
                stopCallback.invoke(receiver)
                return true
            }
        }
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns mockk(relaxed = true)
        every { context.getSystemService(Context.NSD_SERVICE) } returns mockk<NsdManager>(relaxed = true)
        receiver = AirPlayReceiver(
            context = context,
            videoSurfaceProvider = { null },
            onStateChanged = { }
        )
        AirPlayReceiver::class.java.getDeclaredField("rtspHandler").apply {
            isAccessible = true
            set(receiver, handler)
        }
        val oldDecoder = mockk<VideoDecoder>(relaxed = true)
        AirPlayReceiver::class.java.getDeclaredField("videoDecoder").apply {
            isAccessible = true
            set(receiver, oldDecoder)
        }

        try {
            assertTrue(receiver.disconnectActiveSender())
            verify(exactly = 1) { oldDecoder.release() }
        } finally {
            receiver.stop()
            Dispatchers.resetMain()
        }
    }
}
