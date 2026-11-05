package dev.imirror.receiver.miracast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the verified Sink-side M1–M7 exchange over real loopback TCP sockets. */
class WfdSessionHandlerTest {

    @Test
    fun negotiatesWfdSessionAndStartsRtpAfterPlay() {
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val source = Socket(listener.inetAddress, listener.localPort).apply { soTimeout = 5_000 }
        val sink = listener.accept()
        val handler = WfdSessionHandler(sink, rtpPort = 19_000)
        var establishedSession = ""
        var streamStarted = false
        val streamStartedLatch = CountDownLatch(1)
        handler.onSessionEstablished = { establishedSession = it }
        handler.onStreamStart = { port ->
            streamStarted = port == 19_000
            streamStartedLatch.countDown()
            true
        }
        val thread = Thread { handler.handleSession() }

        try {
            thread.start()
            source.send(
                "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n"
            )
            assertTrue(source.readRtsp().startsWith("RTSP/1.0 200 OK"))
            assertTrue(source.readRtsp().startsWith("OPTIONS * RTSP/1.0"))
            source.send("RTSP/1.0 200 OK\r\nCSeq: 1\r\nPublic: org.wfa.wfd1.0\r\n\r\n")

            val capabilityQuery = "wfd_video_formats\r\n"
            source.send(
                "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n" +
                    "CSeq: 2\r\nContent-Length: ${capabilityQuery.length}\r\n\r\n" + capabilityQuery
            )
            val capabilities = source.readRtsp()
            assertTrue(capabilities.contains("wfd_video_formats:"))
            assertTrue(capabilities.contains("wfd_audio_codecs: AAC 00000001 00"))
            assertTrue(
                capabilities.contains(
                    "wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play"
                )
            )
            assertTrue(capabilities.contains("wfd_content_protection: none"))

            val selection =
                "wfd_presentation_URL: rtsp://192.168.49.120/wfd1.0/streamid=0 none\r\n" +
                    "wfd_video_formats: 00 00 02 10 00000080 00000000 00000000 00 0000 0000 00 none none\r\n"
            source.send(
                "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 3\r\n" +
                    "Content-Length: ${selection.length}\r\n\r\n" + selection
            )
            assertTrue(source.readRtsp().startsWith("RTSP/1.0 200 OK"))

            val trigger = "wfd_trigger_method: SETUP\r\n"
            source.send(
                "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\n" +
                    "Content-Length: ${trigger.length}\r\n\r\n" + trigger
            )
            assertTrue(source.readRtsp().startsWith("RTSP/1.0 200 OK"))
            val setup = source.readRtsp()
            assertTrue(setup.startsWith("SETUP rtsp://192.168.49.120/wfd1.0/streamid=0 RTSP/1.0"))
            assertTrue(setup.contains("Transport: RTP/AVP/UDP;unicast;client_port=19000"))

            source.send(
                "RTSP/1.0 200 OK\r\nCSeq: 2\r\nSession: 454568908696\r\n" +
                    "Transport: RTP/AVP/UDP;unicast;server_port=19000\r\n\r\n"
            )
            val play = source.readRtsp()
            assertTrue(play.startsWith("PLAY rtsp://192.168.49.120/wfd1.0/streamid=0 RTSP/1.0"))
            assertTrue(play.contains("Session: 454568908696"))
            assertEquals("454568908696", establishedSession)

            source.send("RTSP/1.0 200 OK\r\nCSeq: 3\r\nSession: 454568908696\r\n\r\n")
            assertTrue("PLAY response was not processed", streamStartedLatch.await(2, TimeUnit.SECONDS))
            assertTrue(streamStarted)
        } finally {
            handler.close()
            source.close()
            listener.close()
            thread.join(3_000)
        }
        assertFalse("WFD handler must exit when its socket closes", thread.isAlive)
    }

    @Test
    fun `SETUP ignores wrong CSeq and rejects non-2xx response`() {
        var sessionEstablished = false
        var streamStarted = false
        val stopped = CountDownLatch(1)
        withHandler(
            configure = {
                onSessionEstablished = { sessionEstablished = true }
                onStreamStart = { streamStarted = true; true }
                onStreamStop = { stopped.countDown() }
            }
        ) { source, _ ->
            source.negotiateUntilSetup()
            source.send("RTSP/1.0 200 OK\r\nCSeq: 99\r\nSession: wrong\r\n\r\n")
            source.send("RTSP/1.0 503 Unavailable\r\nCSeq: 2\r\n\r\n")

            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            assertFalse(sessionEstablished)
            assertFalse(streamStarted)
        }
    }

    @Test
    fun `PLAY ignores wrong CSeq and rejects non-2xx response`() {
        var streamStarted = false
        val stopped = CountDownLatch(1)
        withHandler(
            configure = {
                onStreamStart = { streamStarted = true; true }
                onStreamStop = { stopped.countDown() }
            }
        ) { source, _ ->
            source.negotiateUntilSetup()
            source.send("RTSP/1.0 200 OK\r\nCSeq: 2\r\nSession: 454568908696\r\n\r\n")
            assertTrue(source.readRtsp().startsWith("PLAY "))
            source.send("RTSP/1.0 200 OK\r\nCSeq: 99\r\n\r\n")
            source.send("RTSP/1.0 500 Internal Server Error\r\nCSeq: 3\r\n\r\n")

            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            assertFalse(streamStarted)
        }
    }

    private fun withHandler(
        configure: WfdSessionHandler.() -> Unit,
        test: (Socket, WfdSessionHandler) -> Unit
    ) {
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val source = Socket(listener.inetAddress, listener.localPort).apply { soTimeout = 5_000 }
        val sink = listener.accept()
        val handler = WfdSessionHandler(sink, rtpPort = 19_000).apply(configure)
        val thread = Thread { handler.handleSession() }
        try {
            thread.start()
            test(source, handler)
        } finally {
            handler.close()
            source.close()
            listener.close()
            thread.join(3_000)
        }
        assertFalse("WFD handler must exit after a rejected response", thread.isAlive)
    }

    /** Drives valid M1–M5 and consumes messages through the Sink's SETUP request. */
    private fun Socket.negotiateUntilSetup() {
        send("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n")
        readRtsp()
        readRtsp()
        send("RTSP/1.0 200 OK\r\nCSeq: 1\r\nPublic: org.wfa.wfd1.0\r\n\r\n")

        val query = "wfd_video_formats\r\n"
        send(
            "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\n" +
                "Content-Length: ${query.length}\r\n\r\n" + query
        )
        readRtsp()

        val selection =
            "wfd_presentation_URL: rtsp://192.168.49.120/wfd1.0/streamid=0 none\r\n"
        send(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 3\r\n" +
                "Content-Length: ${selection.length}\r\n\r\n" + selection
        )
        readRtsp()

        val trigger = "wfd_trigger_method: SETUP\r\n"
        send(
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\n" +
                "Content-Length: ${trigger.length}\r\n\r\n" + trigger
        )
        readRtsp()
        readRtsp()
    }

    private fun Socket.send(message: String) {
        getOutputStream().apply {
            write(message.toByteArray(StandardCharsets.UTF_8))
            flush()
        }
    }

    private fun Socket.readRtsp(): String = getInputStream().readRtsp()

    private fun InputStream.readRtsp(): String {
        val header = StringBuilder()
        val one = ByteArray(1)
        while (!header.endsWith("\r\n\r\n")) {
            if (read(one) <= 0) throw java.io.EOFException("RTSP header ended early")
            header.append(one[0].toInt().toChar())
        }
        val contentLength = Regex("(?i)Content-Length:\\s*(\\d+)")
            .find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < body.size) {
            val count = read(body, offset, body.size - offset)
            if (count <= 0) throw java.io.EOFException("RTSP body ended early")
            offset += count
        }
        return header.toString() + String(body, StandardCharsets.UTF_8)
    }
}
