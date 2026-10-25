package it.peppedess.dexreceiver.wfd

import java.io.ByteArrayOutputStream
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
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newCachedThreadPool()
    @Volatile private var listener: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        acceptThread = Thread({
            try {
                val socket = ServerSocket(port)
                listener = socket
                onLog("RTSP in ascolto su TCP $port.")
                while (running.get()) {
                    val client = socket.accept()
                    clients.add(client)
                    workers.execute { handleClient(client) }
                }
            } catch (e: SocketException) {
                if (running.get()) onLog("Socket RTSP chiuso inaspettatamente: ${e.message}")
            } catch (e: Exception) {
                onLog("Errore server RTSP: ${e.message}")
            } finally {
                running.set(false)
                listener = null
            }
        }, "WFD-RTSP-Accept").apply { isDaemon = true; start() }
        return true
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
                            output.write("RTSP/1.0 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
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
        val header = bytes.toByteArray()
        val headerText = String(header, Charsets.ISO_8859_1)
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
        running.set(false)
        try { listener?.close() } catch (_: Exception) {}
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        clients.clear()
        workers.shutdownNow()
        acceptThread = null
    }

    fun isRunning(): Boolean = running.get()
}
