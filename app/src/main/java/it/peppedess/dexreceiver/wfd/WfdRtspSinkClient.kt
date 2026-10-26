package it.peppedess.dexreceiver.wfd

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RTSP control-channel foundation for the Miracast WFD sink role.
 * The sink is the TCP client; the source is the TCP server on port 7236.
 * This first stage implements connection setup and the M1/M2 OPTIONS exchange.
 * WFD capability negotiation (M3/M4/M5) is deliberately left to the next stage.
 */
class WfdRtspSinkClient(
    private val sourceAddress: String,
    private val controlPort: Int = 7236,
    private val onLog: (String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    @Volatile private var worker: Thread? = null
    private var sinkCSeq = 1
    private var m2Sent = false

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        worker = Thread({ runClient() }, "WFD-RTSP-Sink").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        worker?.interrupt()
        worker = null
    }

    fun isRunning(): Boolean = running.get()

    private fun runClient() {
        try {
            val connection = Socket()
            socket = connection
            connection.connect(InetSocketAddress(sourceAddress, controlPort), 10_000)
            connection.soTimeout = 30_000
            onLog("RTSP sink connesso al source $sourceAddress:$controlPort.")
            val input = connection.getInputStream()
            val output = connection.getOutputStream()

            while (running.get() && !connection.isClosed) {
                val raw = readMessage(input) ?: break
                val message = parseMessage(raw)
                if (message == null) {
                    onLog("Messaggio RTSP non valido: chiusura connessione.")
                    break
                }

                if (message.isResponse) {
                    onLog("Risposta RTSP source: ${message.statusCode} · CSeq ${message.cseq}")
                    if (m2Sent && message.statusCode == 200) {
                        onLog("M2 OPTIONS confermato: canale RTSP bidirezionale stabilito.")
                    } else if (message.statusCode != 200) {
                        onLog("Il source ha rifiutato una richiesta RTSP.")
                    }
                    continue
                }

                onLog("Richiesta RTSP source → sink: ${message.method} · CSeq ${message.cseq}")
                when (message.method) {
                    "OPTIONS" -> {
                        writeResponse(
                            output, 200, "OK", message.cseq,
                            listOf("Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER")
                        )
                        // M2 is initiated by the sink immediately after answering M1.
                        if (!m2Sent) {
                            sendM2(output)
                            m2Sent = true
                        }
                    }
                    "GET_PARAMETER", "SET_PARAMETER" -> {
                        // Full WFD M3/M4/M5 processing belongs to stage 2.
                        onLog("Negoziazione WFD ${message.method} ricevuta: prevista nel punto 2.")
                        writeResponse(output, 501, "Not Implemented", message.cseq)
                    }
                    else -> writeResponse(
                        output, 405, "Method Not Allowed", message.cseq,
                        listOf("Allow: OPTIONS, GET_PARAMETER, SET_PARAMETER")
                    )
                }
            }
        } catch (t: Throwable) {
            if (running.get()) onLog("Connessione RTSP sink terminata: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            running.set(false)
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            onLog("Client RTSP sink disconnesso.")
        }
    }

    private fun sendM2(output: OutputStream) {
        val cseq = sinkCSeq++
        val request = "OPTIONS * RTSP/1.0\r\n" +
            "CSeq: $cseq\r\n" +
            "Require: org.wfa.wfd1.0\r\n" +
            "User-Agent: DeX-Receiver/0.1\r\n" +
            "Content-Length: 0\r\n\r\n"
        output.write(request.toByteArray(Charsets.UTF_8))
        output.flush()
        onLog("M2 OPTIONS sink → source inviato · CSeq $cseq.")
    }

    private fun writeResponse(
        output: OutputStream,
        code: Int,
        reason: String,
        cseq: String,
        headers: List<String> = emptyList()
    ) {
        val response = buildString {
            append("RTSP/1.0 $code $reason\r\n")
            append("CSeq: ").append(cseq).append("\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("Content-Length: 0\r\n\r\n")
        }
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
        onLog("Risposta RTSP sink: $code · CSeq $cseq.")
    }

    private data class RtspMessage(
        val isResponse: Boolean,
        val method: String,
        val statusCode: Int,
        val cseq: String,
        val body: String
    )

    private fun parseMessage(raw: String): RtspMessage? {
        val boundary = raw.indexOf("\r\n\r\n")
        if (boundary < 0) return null
        val lines = raw.substring(0, boundary).split("\r\n")
        val first = lines.firstOrNull() ?: return null
        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            val separator = line.indexOf(':')
            if (separator <= 0) return null
            val name = line.substring(0, separator).trim()
            if (headers.keys.any { it.equals(name, true) }) return null
            headers[name] = line.substring(separator + 1).trim()
        }
        val cseq = headers.entries.firstOrNull { it.key.equals("CSeq", true) }?.value ?: return null
        if (cseq.isEmpty() || cseq.any { it !in '0'..'9' }) return null
        val isResponse = first.startsWith("RTSP/1.0 ")
        val status = if (isResponse) first.split(' ').getOrNull(1)?.toIntOrNull() ?: return null else 0
        val method = if (isResponse) "" else {
            val parts = first.split(' ')
            if (parts.size != 3 || parts[2] != "RTSP/1.0") return null
            parts[0].uppercase()
        }
        return RtspMessage(isResponse, method, status, cseq, raw.substring(boundary + 4))
    }

    /** Reads one complete RTSP frame from a TCP byte stream, including its body. */
    private fun readMessage(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        val marker = byteArrayOf(13, 10, 13, 10)
        var matched = 0
        while (bytes.size() <= MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) {
                if (bytes.size() == 0) return null
                throw IllegalStateException("Header RTSP troncato")
            }
            bytes.write(value)
            if (value.toByte() == marker[matched]) {
                matched++
                if (matched == marker.size) break
            } else {
                matched = if (value == 13) 1 else 0
            }
        }
        require(matched == marker.size) { "Header RTSP troppo grande" }
        val header = String(bytes.toByteArray(), Charsets.ISO_8859_1)
        val length = Regex("(?im)^Content-Length\\s*:\\s*(\\d+)\\s*$")
            .find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        require(length in 0..MAX_BODY_BYTES) { "Body RTSP troppo grande" }
        repeat(length) {
            val value = input.read()
            if (value < 0) throw IllegalStateException("Body RTSP troncato")
            bytes.write(value)
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 64 * 1024
    }
}
