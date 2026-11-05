package dev.imirror.receiver.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ActiveConnectionTest {

    @Test
    fun `idle AirPlay callback does not clear active Miracast connection`() {
        val miracast = ActiveConnection("Phone", Protocol.MIRACAST, 1_000L)
        val result = miracast.afterProtocolState(
            Protocol.AIRPLAY,
            ProtocolState.ADVERTISING,
            "AirPlay"
        )
        assertSame(miracast, result)
    }

    @Test
    fun `idle Miracast callback does not clear active AirPlay connection`() {
        val airPlay = ActiveConnection("Mac", Protocol.AIRPLAY, 1_000L)
        val result = airPlay.afterProtocolState(
            Protocol.MIRACAST,
            ProtocolState.ADVERTISING,
            "Miracast"
        )
        assertSame(airPlay, result)
    }

    @Test
    fun `owning protocol teardown clears connection`() {
        val active = ActiveConnection("Phone", Protocol.MIRACAST, 1_000L)
        val result = active.afterProtocolState(
            Protocol.MIRACAST,
            ProtocolState.ADVERTISING,
            "Miracast"
        )
        assertNull(result)
    }

    @Test
    fun `Miracast newcomer transfers ownership from AirPlay`() {
        val active = ActiveConnection("Mac", Protocol.AIRPLAY, 1_000L)
        val result = active.afterProtocolState(
            Protocol.MIRACAST,
            ProtocolState.CONNECTED,
            "Phone"
        )
        assertEquals(Protocol.MIRACAST, result?.protocol)
        assertEquals("Phone", result?.senderName)
    }

    @Test
    fun `connected event transfers ownership to reporting protocol`() {
        val active = ActiveConnection("Phone", Protocol.MIRACAST, 1_000L)
        val result = active.afterProtocolState(
            Protocol.AIRPLAY,
            ProtocolState.CONNECTED,
            "Mac"
        )
        assertEquals(Protocol.AIRPLAY, result?.protocol)
        assertEquals("Mac", result?.senderName)
    }
}
