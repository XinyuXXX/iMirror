package dev.imirror.receiver.service

import dev.imirror.receiver.airplay.AirPlayReceiver
import dev.imirror.receiver.airplay.NowPlayingInfo
import dev.imirror.receiver.miracast.MiracastReceiver
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class MirrorServiceLastWinsTest {

    @Test
    fun `Miracast newcomer cleans up AirPlay and transient overlays before acceptance`() {
        val service = MirrorService()
        val airPlay = mockk<AirPlayReceiver>()
        every { airPlay.disconnectActiveSender() } returns true
        service.setField("airPlayReceiver", airPlay)
        service.setState("_activeConnection", ActiveConnection("Mac", Protocol.AIRPLAY))
        service.setState("_photoFrame", PhotoFrame(byteArrayOf(1), "image/jpeg"))
        service.setState("_nowPlaying", mockk<NowPlayingInfo>())
        service.setState("_pairingPin", "1234")

        val result = MirrorService::class.java.getDeclaredMethod("prepareForMiracastConnection")
            .apply { isAccessible = true }
            .invoke(service) as Boolean

        assertTrue(result)
        verify(exactly = 1) { airPlay.disconnectActiveSender() }
        assertNull(service.photoFrame.value)
        assertNull(service.nowPlaying.value)
        assertNull(service.pairingPin.value)
    }

    @Test
    fun `AirPlay newcomer synchronously disconnects old Miracast media`() {
        val service = MirrorService()
        val miracast = mockk<MiracastReceiver>()
        every { miracast.disconnectSenderAndWait() } just Runs
        service.setField("miracastReceiver", miracast)
        service.setState("_activeConnection", ActiveConnection("Phone", Protocol.MIRACAST))

        MirrorService::class.java.getDeclaredMethod("prepareForAirPlayConnection")
            .apply { isAccessible = true }
            .invoke(service)

        verify(exactly = 1) { miracast.disconnectSenderAndWait() }
    }

    @Test
    fun `Miracast is not accepted if old AirPlay cleanup fails`() {
        val service = MirrorService()
        val airPlay = mockk<AirPlayReceiver>()
        every { airPlay.disconnectActiveSender() } returns false
        service.setField("airPlayReceiver", airPlay)
        service.setState("_activeConnection", ActiveConnection("Mac", Protocol.AIRPLAY))

        val result = MirrorService::class.java.getDeclaredMethod("prepareForMiracastConnection")
            .apply { isAccessible = true }
            .invoke(service) as Boolean

        assertFalse(result)
        verify(exactly = 1) { airPlay.disconnectActiveSender() }
    }

    private fun MirrorService.setField(name: String, value: Any?) {
        MirrorService::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(this@setField, value)
        }
    }

    private fun <T> MirrorService.setState(name: String, value: T) {
        @Suppress("UNCHECKED_CAST")
        val flow = MirrorService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.get(this) as MutableStateFlow<T>
        flow.value = value
    }
}
