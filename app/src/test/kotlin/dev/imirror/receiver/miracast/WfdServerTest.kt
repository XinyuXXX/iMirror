package dev.imirror.receiver.miracast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WfdServerTest {

    @Test
    fun `disconnectSenderAndWait releases old RTP before returning and keeps server running`() {
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val source = Socket(listener.inetAddress, listener.localPort).apply { soTimeout = 5_000 }
        val sink = listener.accept()
        val server = WfdServer(surfaceProvider = { null }, port = 0, rtpPort = 0)
        val stopped = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        server.onConnectionRequested = { _, _ -> true }
        server.onStreamStopped = { stopped.countDown() }
        val runSession = WfdServer::class.java.getDeclaredMethod("runSession", Socket::class.java)
            .apply { isAccessible = true }
        val thread = Thread {
            try {
                runSession.invoke(server, sink)
            } catch (e: Throwable) {
                failure.set(e)
            }
        }

        try {
            server.start()
            thread.start()
            source.send("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n")
            assertTrue(source.readRtspHeader().startsWith("RTSP/1.0 200 OK"))

            server.disconnectSenderAndWait()

            assertNull("old RTP receiver must be released before handoff returns", RtpReceiver.active)
            assertTrue(sink.isClosed)
            assertTrue("disconnecting one sender must not stop WFD discovery", server.isRunning())
            thread.join(3_000)
            assertFalse(thread.isAlive)
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            assertNull(failure.get())
        } finally {
            server.stop()
            runCatching { source.close() }
            runCatching { sink.close() }
            listener.close()
            thread.join(3_000)
        }
    }

    @Test
    fun `stop during connection callback leaves no orphan RTP or RTSP session`() {
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val source = Socket(listener.inetAddress, listener.localPort)
        val sink = listener.accept()
        val server = WfdServer(surfaceProvider = { null }, port = 0, rtpPort = 0)
        val callbackEntered = CountDownLatch(1)
        val finishCallback = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        var streamStarted = false
        server.onConnectionRequested = { _, _ ->
            callbackEntered.countDown()
            finishCallback.await(2, TimeUnit.SECONDS)
            true
        }
        server.onStreamStarted = {
            streamStarted = true
            true
        }
        val runSession = WfdServer::class.java.getDeclaredMethod("runSession", Socket::class.java)
            .apply { isAccessible = true }
        val thread = Thread {
            try {
                runSession.invoke(server, sink)
            } catch (e: Throwable) {
                failure.set(e)
            }
        }

        try {
            server.start()
            thread.start()
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))

            server.stop()
            server.stop()
            assertTrue("stop must close the registered RTSP socket", sink.isClosed)
            finishCallback.countDown()
            thread.join(3_000)

            assertFalse(thread.isAlive)
            assertFalse(streamStarted)
            assertNull(RtpReceiver.active)
            assertNull(failure.get())
        } finally {
            finishCallback.countDown()
            server.stop()
            runCatching { source.close() }
            runCatching { sink.close() }
            listener.close()
            thread.join(3_000)
        }
    }

    private fun Socket.send(message: String) {
        getOutputStream().apply {
            write(message.toByteArray(Charsets.UTF_8))
            flush()
        }
    }

    private fun Socket.readRtspHeader(): String {
        val result = StringBuilder()
        val one = ByteArray(1)
        while (!result.endsWith("\r\n\r\n")) {
            if (getInputStream().read(one) <= 0) throw java.io.EOFException("RTSP ended early")
            result.append(one[0].toInt().toChar())
        }
        return result.toString()
    }
}
