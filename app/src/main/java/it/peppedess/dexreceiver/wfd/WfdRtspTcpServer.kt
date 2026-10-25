package it.peppedess.dexreceiver.wfd

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local RTSP TCP endpoint on the Wi-Fi Display control port.
 *
 * This is deliberately not advertised as a Miracast sink: the session layer
 * rejects WFD negotiation and media setup until those features are implemented.
 */
class WfdRtspTcpServer(
    private val port: Int = 7236,
    private val onLog: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var workers = Executors.newCachedThreadPool()
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    /**
     * Bind before reporting success. This avoids the previous race where start()
     * returned true even if the port was occupied, and where stop() could run
     * before the accept thread had published its ServerSocket.
     */
    fun start(): Boolean = synchronized(lifecycleLock) {
        if (running.get()) return false
        if (workers.isShutdown) workers = Executors.newCachedThreadPool()

        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: Exception) {
            onLog("Impossibile aprire TCP $port: ${e.message}")
            return false
        }

        listener = socket
        running.set(true)
        acceptThread = Thread({ acceptLoop(socket) }, "WFD-RTSP-Accept").apply {
            isDaemon = true
            start()
        }
        true
    }

    private fun acceptLoop(socket: ServerSocket) {
        try {
            onLog("RTSP in ascolto su TCP $port.")
            while (running.get() && !socket.isClosed) {
                val client = socket.accept()
                if (!running.get()) {
                    client.close()
                    break
                }
                clients.add(client)
                try {
                    workers.execute { handleClient(client) }
                } catch (e: Exception) {
                    clients.remove(client)
                    try { client.close() } catch (_: Exception) {}
                    if (running.get()) onLog("Impossibile gestire il client RTSP: ${e.message}")
                }
            }
        } catch (e: SocketException) {
            if (running.get()) onLog("Socket RTSP chiuso inaspettatamente: ${e.message}")
        } catch (e: Exception) {
            if (running.get()) onLog("Errore server RTSP: ${e.message}")
        } finally {
            synchronized(lifecycleLock) {
                if (listener === socket) {
                    running.set(false)
                    listener = null
                    acceptThread = null
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 15_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val session = WfdRtspSession()
                onLog("Client RTSP connesso: ${socket.inetAddress.hostAddress}")
                while (running.get() && !session.isClosed()) {
                    val message = readMessage(input) ?: break
                    when (val parsed = WfdRtspParser.parse(message)) {
                        is RtspParseResult.Request -> {
                            val request = parsed.value
                            onLog("RTSP ${request.method} · CSeq ${request.cSeq ?: "?"}")
                            val response = session.handle(request)
                            output.write(response.toByteArray(Charsets.UTF_8))
                            output.flush()
                            if (session.isClosed()) break
                        }
                        is RtspParseResult.Error -> {
                            onLog("Richiesta RTSP non valida: ${parsed.reason}")
                            output.write("RTSP/1.0 400 Bad Request\r\nCSeq: ${parsedSequence(message)}\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.UTF_8))
                            output.flush()
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) onLog("Client RTSP terminato: ${e.message}")
            } finally {
                clients.remove(client)
                onLog("Client RTSP disconnesso.")
            }
        }
    }


    private fun parsedSequence(message: String): String =
        Regex("(?im)^CSeq\\s*:\\s*([0-9]+)\\s*$")
            .find(message)?.groupValues?.get(1) ?: "0"

    /** Reads exactly one RTSP message, including its declared body. */
    private fun readMessage(input: java.io.InputStream): String? {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        val marker = byteArrayOf(13, 10, 13, 10)
        while (bytes.size() <= 16 * 1024) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else throw IllegalArgumentException("Header RTSP troncato")
            bytes.write(value)
            if (value.toByte() == marker[matched]) {
                matched++
                if (matched == marker.size) break
            } else {
                matched = if (value == 13) 1 else 0
            }
        }
        if (matched != marker.size) throw IllegalArgumentException("Header RTSP troppo grande")
        val headerText = String(bytes.toByteArray(), Charsets.ISO_8859_1)
        val contentLength = Regex("(?im)^Content-Length\\s*:\\s*(\\d+)\\s*$")
            .find(headerText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (contentLength > 64 * 1024) throw IllegalArgumentException("Body RTSP troppo grande")
        repeat(contentLength) {
            val b = input.read()
            if (b < 0) throw IllegalArgumentException("Body RTSP troncato")
            bytes.write(b)
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    fun stop() {
        synchronized(lifecycleLock) {
            running.set(false)
            try { listener?.close() } catch (_: Exception) {}
            listener = null
            clients.forEach { try { it.close() } catch (_: Exception) {} }
            clients.clear()
            workers.shutdownNow()
            acceptThread = null
        }
    }

    fun isRunning(): Boolean = running.get()
}
