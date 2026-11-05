package dev.imirror.receiver.miracast

import android.view.Surface
import kotlinx.coroutines.*
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Wi-Fi Display (Miracast) 会话发起器。
 *
 * 注意这里**不是** RTSP 服务器 —— WFD 规范里 Source 才是监听方。实测（Windows 11
 * MSMiracastSource）确认：Source 接入 P2P 组后会在自己的 7236 端口等待，Sink 必须
 * 主动连过去，否则双方互等到超时（约 60 秒）后断开。
 *
 * 因此本类的职责是：
 *   1. 在 P2P 网段里找出正在监听 7236 的 Source
 *   2. 连过去，交给 [WfdSessionHandler] 跑 M1–M7
 *   3. 会话期间用 [RtpReceiver] 收 RTP（MPEG-2 TS）
 *
 * 在系统已广播 WFD IE 的电视（例如已实测的 Sony BRAVIA）上不需要 root。
 * 本类只使用公开网络 API，不修改系统 Wi-Fi Display 设置。
 *
 * @param port Source 的 RTSP 监听端口（WFD 标准 7236）
 * @param rtpPort 本机接收 RTP 的 UDP 端口
 */
class WfdServer(
    private val surfaceProvider: () -> Surface?,
    private val port: Int = 7236,
    private val rtpPort: Int = 19000
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isRunning = false
    @Volatile private var reconnectAfterMs = 0L

    // All session fields are guarded by this lock, including the dial→runSession handoff.
    private val sessionLock = Any()
    private var activeSocket: Socket? = null
    private var sessionHandler: WfdSessionHandler? = null
    private var rtpReceiver: RtpReceiver? = null

    /** Return false to reject a source before allocating RTP/codec resources. */
    var onConnectionRequested: ((clientName: String, clientAddress: String) -> Boolean)? = null
    var onConnectionEstablished: ((sessionId: String) -> Unit)? = null
    /** Return false if another protocol acquired video while RTSP was negotiating. */
    var onStreamStarted: ((rtpPort: Int) -> Boolean)? = null
    var onStreamStopped: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    companion object {
        private const val SCAN_INTERVAL_MS = 3_000L
        private const val CONNECT_TIMEOUT_MS = 400
        private const val SCAN_CHUNK = 32          // 每批并发探测的地址数
    }

    fun start() {
        if (isRunning) {
            Timber.w("WFD session starter already running")
            return
        }
        isRunning = true
        Timber.i("WFD session starter running (will dial source:$port, RTP on $rtpPort)")

        scope.launch {
            while (isRunning) {
                try {
                    if (android.os.SystemClock.elapsedRealtime() < reconnectAfterMs) {
                        delay(SCAN_INTERVAL_MS)
                        continue
                    }
                    val socket = dialSource()
                    if (socket != null) {
                        runSession(socket)
                    }
                } catch (e: Exception) {
                    if (isRunning) Timber.e(e, "WFD session loop error")
                }
                if (isRunning) delay(SCAN_INTERVAL_MS)
            }
        }
    }

    /**
     * 在 P2P 网段里找到 Source 并**保持**那条连接。
     *
     * 关键：不能「探测后关闭再重连」—— Source 的监听队列只接受一次连接，探测连接会被它
     * 当成 Sink 正式接入，随后 listener 就关闭了；等我们再连时必然 ECONNREFUSED，
     * 而 Source 那边看到会话刚建立就断开，直接报"连接失败"。
     * 所以扫描成功的那条 socket 直接用作会话连接。
     */
    private suspend fun dialSource(): Socket? = coroutineScope {
        val prefix = p2pSubnetPrefix() ?: return@coroutineScope null

        for (chunkStart in 2..254 step SCAN_CHUNK) {
            if (!isRunning) return@coroutineScope null
            val range = chunkStart until minOf(chunkStart + SCAN_CHUNK, 255)
            val connected = range.map { host ->
                async { tryConnect("$prefix$host") }
            }.awaitAll().filterNotNull()

            if (connected.isNotEmpty()) {
                val session = connected.first()
                connected.drop(1).forEach { runCatching { it.close() } }
                Timber.i("WFD: connected to source at ${session.inetAddress.hostAddress}:$port")
                return@coroutineScope session
            }
        }
        null
    }

    private fun tryConnect(ip: String): Socket? = try {
        Socket().also { it.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS) }
    } catch (e: Exception) {
        null
    }

    /**
     * 取本机 P2P 接口的 /24 前缀（通常是 "192.168.49."）。
     * 组主地址本身要排除掉，剩下的就是 Source 可能拿到的地址。
     */
    private fun p2pSubnetPrefix(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .firstOrNull { it.name.startsWith("p2p") && it.isUp }
            ?.inetAddresses?.toList()
            ?.firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress
            ?.substringBeforeLast('.')
            ?.plus(".")
    } catch (e: Exception) {
        Timber.e(e, "Failed to resolve P2P subnet")
        null
    }

    /** 在已连接的 socket 上跑完整个会话；结束后返回，外层循环继续等待下一次投屏。 */
    private fun runSession(socket: Socket) {
        var receiver: RtpReceiver? = null
        var handler: WfdSessionHandler? = null
        try {
            synchronized(sessionLock) {
                if (!isRunning || activeSocket != null) return
                activeSocket = socket
            }

            val sourceIp = socket.inetAddress.hostAddress ?: "unknown"
            if (onConnectionRequested?.invoke("Miracast", sourceIp) == false) {
                Timber.w("WFD: rejecting $sourceIp because another video session is active")
                reconnectAfterMs = android.os.SystemClock.elapsedRealtime() + 5000
                return
            }

            var startError: String? = null
            synchronized(sessionLock) {
                // stop() either wins before this block (no resources are created), or sees and
                // closes the fully registered resources after this block releases the lock.
                if (!isRunning || activeSocket !== socket) return
                try {
                    val newReceiver = RtpReceiver(rtpPort, surfaceProvider).apply {
                        onError = {
                            Timber.e("Miracast RTP error: $it")
                            this@WfdServer.onError?.invoke(it)
                        }
                    }
                    receiver = newReceiver
                    newReceiver.start()       // UDP bind completes before RTSP M3 can be handled.
                    val newHandler = WfdSessionHandler(socket, rtpPort).apply {
                        onSessionEstablished = { onConnectionEstablished?.invoke(it) }
                        onStreamStart = { onStreamStarted?.invoke(it) != false }
                        onStreamStop = { onStreamStopped?.invoke() }
                    }
                    handler = newHandler
                    rtpReceiver = newReceiver
                    sessionHandler = newHandler
                } catch (e: Exception) {
                    startError = e.message ?: "RTP/WFD session initialization failed"
                    Timber.e(e, "Failed to initialize WFD media session")
                }
            }

            val currentHandler = handler
            if (currentHandler == null) {
                if (isRunning) startError?.let { onError?.invoke(it) }
                return
            }
            currentHandler.handleSession()      // 阻塞直到会话结束
        } finally {
            synchronized(sessionLock) {
                if (activeSocket === socket) activeSocket = null
                if (rtpReceiver === receiver) rtpReceiver = null
                if (sessionHandler === handler) sessionHandler = null
            }
            runCatching { socket.close() }
            receiver?.stop()                    // idempotent with stop()
        }
    }

    fun stop() {
        val socket: Socket?
        val handler: WfdSessionHandler?
        val receiver: RtpReceiver?
        synchronized(sessionLock) {
            isRunning = false
            socket = activeSocket
            handler = sessionHandler
            receiver = rtpReceiver
            activeSocket = null
            sessionHandler = null
            rtpReceiver = null
        }
        scope.cancel()
        runCatching { socket?.close() }
        runCatching { handler?.close() }
        receiver?.stop()
        Timber.i("WFD session starter stopped")
    }

    fun isRunning(): Boolean = isRunning

    fun disconnectSender() {
        reconnectAfterMs = android.os.SystemClock.elapsedRealtime() + 5000
        val current: WfdSessionHandler?
        val socket: Socket?
        synchronized(sessionLock) {
            current = sessionHandler
            socket = activeSocket
        }
        scope.launch {
            if (current != null) runCatching { current.disconnectSender() }
            else runCatching { socket?.close() }
        }
    }

    /** Last-wins handoff: old RTP/demux/decoder resources are released before returning. */
    fun disconnectSenderAndWait() {
        reconnectAfterMs = android.os.SystemClock.elapsedRealtime() + 5000
        val current: WfdSessionHandler?
        val socket: Socket?
        val receiver: RtpReceiver?
        synchronized(sessionLock) {
            current = sessionHandler
            socket = activeSocket
            receiver = rtpReceiver
        }
        if (current != null) runCatching { current.disconnectSender() }
        runCatching { socket?.close() }
        receiver?.stop()
    }
}
