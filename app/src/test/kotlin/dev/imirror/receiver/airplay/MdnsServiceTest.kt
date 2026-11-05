package dev.imirror.receiver.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.imirror.receiver.service.ProtocolState
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Models NsdManager's real serialized registration callback lifecycle. */
class MdnsServiceTest {

    private lateinit var mdnsService: MdnsService
    private lateinit var mockContext: Context
    private lateinit var mockNsdManager: NsdManager
    private lateinit var listeners: MutableList<NsdManager.RegistrationListener>
    private lateinit var states: MutableList<ProtocolState>

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockNsdManager = mockk(relaxed = true)
        listeners = mutableListOf()
        states = mutableListOf()
        every { mockContext.getSystemService(Context.NSD_SERVICE) } returns mockNsdManager
        every { mockContext.getSharedPreferences(any(), any()) } returns mockk(relaxed = true)
        every {
            mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, capture(listeners))
        } just Runs
        mdnsService = MdnsService(mockContext, onStateChange = { states += it })
    }

    @Test
    fun `start registers RAOP first and AirPlay only after RAOP succeeds`() {
        mdnsService.start("Test TV")
        verify(exactly = 1) { mockNsdManager.registerService(any(), any(), any()) }

        listeners[0].registered("AA@Test TV")

        verify(exactly = 2) { mockNsdManager.registerService(any(), any(), any()) }
    }

    @Test
    fun `duplicate start and registration callbacks do not duplicate services or state`() {
        mdnsService.start()
        mdnsService.start()
        verify(exactly = 1) { mockNsdManager.registerService(any(), any(), any()) }

        listeners[0].registered("raop")
        listeners[0].registered("duplicate raop")
        listeners[1].registered("AirPlay")
        listeners[1].registered("duplicate AirPlay")

        verify(exactly = 2) { mockNsdManager.registerService(any(), any(), any()) }
        assertEquals(1, states.count { it == ProtocolState.ADVERTISING })
    }

    @Test
    fun `stop unregisters both services after serialized registration`() {
        mdnsService.start()
        listeners[0].registered("raop")

        mdnsService.stop()

        verify(exactly = 1) { mockNsdManager.unregisterService(listeners[1]) }
        verify(exactly = 1) { mockNsdManager.unregisterService(listeners[0]) }
    }

    @Test
    fun `one unregister exception does not block the other and repeated stop is clean`() {
        mdnsService.start()
        listeners[0].registered("raop")
        val raop = listeners[0]
        val airPlay = listeners[1]
        every { mockNsdManager.unregisterService(raop) } throws
            IllegalArgumentException("listener not registered")

        mdnsService.stop()
        mdnsService.stop()

        verify(exactly = 1) { mockNsdManager.unregisterService(airPlay) }
        verify(exactly = 1) { mockNsdManager.unregisterService(raop) }
        verify(exactly = 2) { mockNsdManager.unregisterService(any()) }
    }

    @Test
    fun `late RAOP success after stop cannot chain AirPlay or advertise`() {
        mdnsService.start()
        val lateRaop = listeners[0]
        mdnsService.stop()

        lateRaop.registered("late")

        verify(exactly = 1) { mockNsdManager.registerService(any(), any(), any()) }
        assertFalse(states.contains(ProtocolState.ADVERTISING))
    }

    @Test
    fun `restart invalidates old callbacks and only new generation reaches advertising`() {
        mdnsService.start()
        val oldRaop = listeners[0]
        mdnsService.restart()
        val newRaop = listeners[1]

        oldRaop.registered("stale")
        verify(exactly = 2) { mockNsdManager.registerService(any(), any(), any()) }
        assertFalse(states.contains(ProtocolState.ADVERTISING))

        newRaop.registered("new raop")
        listeners[2].registered("new AirPlay")

        verify(exactly = 3) { mockNsdManager.registerService(any(), any(), any()) }
        assertEquals(1, states.count { it == ProtocolState.ADVERTISING })
        verify(exactly = 1) { mockNsdManager.unregisterService(oldRaop) }
    }

    @Test
    fun `max-limit failure stays ERROR and clean restart has no stale listener`() {
        mdnsService.start()
        listeners[0].onRegistrationFailed(mockk(), NsdManager.FAILURE_MAX_LIMIT)

        assertTrue(states.contains(ProtocolState.ERROR))
        assertFalse(states.contains(ProtocolState.ADVERTISING))
        verify(exactly = 0) { mockNsdManager.unregisterService(any()) }
        verify(exactly = 1) { mockNsdManager.registerService(any(), any(), any()) }

        mdnsService.restart()
        listeners[1].registered("new raop")
        listeners[2].registered("new AirPlay")

        verify(exactly = 3) { mockNsdManager.registerService(any(), any(), any()) }
        assertEquals(1, states.count { it == ProtocolState.ADVERTISING })
        mdnsService.stop()
        verify(exactly = 1) { mockNsdManager.unregisterService(listeners[1]) }
        verify(exactly = 1) { mockNsdManager.unregisterService(listeners[2]) }
        verify(exactly = 2) { mockNsdManager.unregisterService(any()) }
    }

    @Test
    fun `stop without start does not crash`() {
        mdnsService.stop()
    }

    @Test
    fun `stop emits DISABLED protocol state`() {
        mdnsService.stop()
        assertTrue(states.contains(ProtocolState.DISABLED))
    }

    @Test
    fun `AIRPLAY_PORT is 7000`() {
        assertEquals(7000, MdnsService.AIRPLAY_PORT)
    }

    private fun NsdManager.RegistrationListener.registered(name: String) {
        val serviceInfo = mockk<NsdServiceInfo>()
        every { serviceInfo.serviceName } returns name
        onServiceRegistered(serviceInfo)
    }
}
