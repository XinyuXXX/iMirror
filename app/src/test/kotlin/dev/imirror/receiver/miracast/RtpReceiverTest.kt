package dev.imirror.receiver.miracast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.SocketException

class RtpReceiverTest {

    @Test
    fun `UDP bind failure is synchronous and leaves receiver stopped`() {
        val occupied = DatagramSocket(0)
        val receiver = RtpReceiver(occupied.localPort) { null }
        try {
            assertThrows(SocketException::class.java) { receiver.start() }
            assertFalse(receiver.isRunning())
            assertNull(RtpReceiver.active)
        } finally {
            receiver.stop()
            occupied.close()
        }
    }

    @Test
    fun `stop releases jobs and is idempotent`() {
        val receiver = RtpReceiver(0) { null }
        receiver.start()
        assertTrue(receiver.isRunning())

        receiver.stop()
        receiver.stop()

        assertFalse(receiver.isRunning())
        assertNull(RtpReceiver.active)
    }
}
