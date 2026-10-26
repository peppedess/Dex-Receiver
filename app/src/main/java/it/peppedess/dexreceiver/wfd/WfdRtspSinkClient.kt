package it.peppedess.dexreceiver.wfd

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Miracast/WFD sink-side RTSP control channel.
 * Handles source M1, sink M2, source M3, sink M4, source M5, source M6/M7.
 * Audio is explicitly not advertised; video capability is the conservative AVC baseline.
 */
class WfdRtspSinkClient(
    private val sourceAddress: String,
    private val controlPort: Int = 7236,
    private val mediaPort: Int = 19000,
    private val onStartMedia: (Int) -> Boolean = { false },
    private val onStopMedia: () -> Unit = {},
    private val onPauseMedia: () -> Unit = {},
    private val onResumeMedia: () -> Unit = {},
    private val onLog: (String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    @Volatile private var worker: Thread? = null
    private var sinkCSeq = 1
    private var m2Sent = false
    private var m3Answered = false
    private var m5Seen = false
    private var setup = false
    private var playing = false
    private var sessionId: String? = null
    private var presentationUrl: String? = null

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
        if (setup) onStopMedia()
        setup = false
        playing = false
    }

    fun isRunning(): Boolean = running.get()

    private fun runClient() {
        try {
            val connection = Socket()
            socket = connection
            connection.connect(InetSocketAddress(sourceAddress, controlPort), 10_000)
            connection.soTimeout = 60_000
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
                    continue
                }
                onLog("Richiesta RTSP source → sink: ${message.method} · CSeq ${message.cseq}")
                handleSourceRequest(message, output)
            }
        } catch (t: Throwable) {
            if (running.get()) onLog("Connessione RTSP sink terminata: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            running.set(false)
            if (setup) onStopMedia()
            setup = false
            playing = false
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            onLog("Client RTSP sink disconnesso.")
        }
    }

    private fun handleSourceRequest(message: RtspMessage, output: OutputStream) {
        when (message.method) {
            "OPTIONS" -> {
                writeResponse(output, 200, "OK", message.cseq,
                    listOf("Public: org.wfa.wfd1.0, OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, PAUSE, TEARDOWN"))
                if (!m2Sent) {
                    sendM2(output)
                    m2Sent = true
                }
            }
            "GET_PARAMETER" -> handleM3(message, output)
            "SET_PARAMETER" -> handleM5AndTriggers(message, output)
            "SETUP" -> handleSetup(message, output)
            "PLAY" -> handlePlay(message, output)
            "PAUSE" -> {
                if (!setup || !playing) writeResponse(output, 455, "Method Not Valid in This State", message.cseq)
                else {
                    onPauseMedia()
                    playing = false
                    writeResponse(output, 200, "OK", message.cseq, sessionHeader())
                }
            }
            "TEARDOWN" -> {
                if (setup) onStopMedia()
                setup = false
                playing = false
                writeResponse(output, 200, "OK", message.cseq, sessionHeader())
                running.set(false)
                try { socket?.close() } catch (_: Exception) {}
            }
            else -> writeResponse(output, 405, "Method Not Allowed", message.cseq,
                listOf("Allow: OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, PAUSE, TEARDOWN"))
        }
    }

    /** M3: source requests the sink's WFD capabilities; M4 is this response. */
    private fun handleM3(message: RtspMessage, output: OutputStream) {
        val requested = message.body.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() }.map { it.substringBefore(':').trim().lowercase() }.toSet()
        val capabilities = linkedMapOf(
            "wfd_video_formats" to "wfd_video_formats: $VIDEO_FORMAT",
            "wfd_audio_codecs" to "wfd_audio_codecs: none",
            "wfd_3d_video_formats" to "wfd_3d_video_formats: none",
            "wfd_content_protection" to "wfd_content_protection: none",
            "wfd_display_edid" to "wfd_display_edid: none",
            "wfd_coupled_sink" to "wfd_coupled_sink: none",
            "wfd_client_rtp_ports" to "wfd_client_rtp_ports: RTP/AVP/UDP;unicast $mediaPort 0 mode=play",
            "wfd_uibc_capability" to "wfd_uibc_capability: none",
            "wfd_standby_resume_capability" to "wfd_standby_resume_capability: none",
            "wfd_connector_type" to "wfd_connector_type: 05",
            "wfd_idr_request_capability" to "wfd_idr_request_capability: 1"
        )
        val effectiveRequested = if (requested.isEmpty()) capabilities.keys else requested
        val unknown = effectiveRequested - capabilities.keys
        if (unknown.isNotEmpty()) {
            writeResponse(output, 451, "Parameter Not Understood", message.cseq)
            onLog("M3 contiene parametri WFD non supportati: ${unknown.joinToString()}.")
            return
        }
        val body = effectiveRequested.mapNotNull(capabilities::get).joinToString("\r\n", postfix = "\r\n")
        writeResponse(output, 200, "OK", message.cseq,
            listOf("Content-Type: text/parameters"), body)
        m3Answered = true
        onLog("M4 capability response inviata: AVC baseline, RTP $mediaPort, audio non dichiarato.")
    }

    /** M5: SET_PARAMETER with wfd_trigger_method: SETUP (or subsequent controls). */
    private fun handleM5AndTriggers(message: RtspMessage, output: OutputStream) {
        val params = parseParameters(message.body)
        if (params == null) {
            writeResponse(output, 400, "Bad Request", message.cseq)
            return
        }
        val unknown = params.keys - SUPPORTED_PARAMETERS
        if (unknown.isNotEmpty()) {
            writeResponse(output, 451, "Parameter Not Understood", message.cseq)
            onLog("SET_PARAMETER rifiutato: ${unknown.joinToString()}.")
            return
        }
        val selectedVideo = params["wfd_video_formats"]
        if (selectedVideo != null && !sameDescriptor(selectedVideo, VIDEO_FORMAT)) {
            writeResponse(output, 451, "Parameter Not Understood", message.cseq)
            onLog("Il source ha selezionato un profilo AVC non supportato.")
            return
        }
        val selectedAudio = params["wfd_audio_codecs"]
        if (selectedAudio != null && !selectedAudio.equals("none", true)) {
            writeResponse(output, 451, "Parameter Not Understood", message.cseq)
            onLog("Audio non negoziato: il sink dichiara solo audio none.")
            return
        }
        val url = params.entries.firstOrNull { it.key == "wfd_presentation_url" }?.value
            ?.substringBefore(" none")?.trim()
        if (url != null && !url.startsWith("rtsp://", true)) {
            writeResponse(output, 400, "Bad Request", message.cseq)
            return
        }
        val trigger = params["wfd_trigger_method"]?.uppercase()
        if (trigger != null && trigger !in setOf("SETUP", "PLAY", "PAUSE", "TEARDOWN", "IDR")) {
            writeResponse(output, 451, "Parameter Not Understood", message.cseq)
            return
        }
        if (url != null) presentationUrl = url
        writeResponse(output, 200, "OK", message.cseq)
        if (trigger == "SETUP") {
            m5Seen = true
            onLog("M5 ricevuto: trigger SETUP confermato; in attesa della richiesta SETUP RTSP.")
        } else if (trigger != null) {
            onLog("Trigger WFD ricevuto: $trigger.")
        }
    }

    /** M6: source asks the sink to prepare the negotiated RTP/UDP receive ports. */
    private fun handleSetup(message: RtspMessage, output: OutputStream) {
        if (!m3Answered || !m5Seen || setup) {
            writeResponse(output, 455, "Method Not Valid in This State", message.cseq)
            return
        }
        val transport = message.headers.entries.firstOrNull { it.key.equals("Transport", true) }?.value
        if (transport == null || !transport.contains("RTP/AVP/UDP", true) ||
            !transport.contains("unicast", true) || !transport.contains("mode=play", true)) {
            writeResponse(output, 461, "Unsupported Transport", message.cseq)
            return
        }
        val portMatch = Regex("client_port\\s*=\\s*(\\d+)(?:-(\\d+))?", RegexOption.IGNORE_CASE).find(transport)
        val requestedRtp = portMatch?.groupValues?.get(1)?.toIntOrNull()
        val requestedRtcp = portMatch?.groupValues?.get(2)?.toIntOrNull()
        if (requestedRtp != mediaPort || (requestedRtcp != null && requestedRtcp != 0 && requestedRtcp != mediaPort + 1)) {
            writeResponse(output, 461, "Unsupported Transport", message.cseq)
            onLog("SETUP porta RTP non coincidente con quella annunciata ($mediaPort).")
            return
        }
        if (!onStartMedia(mediaPort)) {
            writeResponse(output, 500, "Internal Server Error", message.cseq)
            return
        }
        setup = true
        sessionId = java.lang.Long.toHexString(System.nanoTime())
        val headers = listOf(
            "Session: ${sessionId};timeout=60",
            "Transport: RTP/AVP/UDP;unicast;client_port=$mediaPort-${mediaPort + 1};mode=play"
        )
        writeResponse(output, 200, "OK", message.cseq, headers)
        onLog("M6 SETUP accettato: ricezione RTP/RTCP su $mediaPort/${mediaPort + 1}.")
    }

    /** M7: source starts the media stream. */
    private fun handlePlay(message: RtspMessage, output: OutputStream) {
        if (!setup || playing) {
            writeResponse(output, 455, "Method Not Valid in This State", message.cseq)
            return
        }
        onResumeMedia()
        playing = true
        writeResponse(output, 200, "OK", message.cseq,
            sessionHeader() + listOf("Range: npt=now-"))
        onLog("M7 PLAY accettato: flusso media attivo.")
    }

    private fun sessionHeader(): List<String> = sessionId?.let { listOf("Session: $it;timeout=60") } ?: emptyList()

    private fun sendM2(output: OutputStream) {
        val cseq = sinkCSeq++
        val request = "OPTIONS * RTSP/1.0\r\n" +
            "CSeq: $cseq\r\n" +
            "Require: org.wfa.wfd1.0\r\n" +
            "User-Agent: DeX-Receiver/0.2\r\n" +
            "Content-Length: 0\r\n\r\n"
        output.write(request.toByteArray(Charsets.UTF_8))
        output.flush()
        onLog("M2 OPTIONS sink → source inviato · CSeq $cseq.")
    }

    private fun writeResponse(
        output: OutputStream, code: Int, reason: String, cseq: String,
        headers: List<String> = emptyList(), body: String = ""
    ) {
        val response = buildString {
            append("RTSP/1.0 $code $reason\r\n")
            append("CSeq: ").append(cseq).append("\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("Content-Length: ").append(body.toByteArray(Charsets.UTF_8).size).append("\r\n\r\n")
            append(body)
        }
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
        onLog("Risposta RTSP sink: $code · CSeq $cseq.")
    }

    private fun parseParameters(body: String): Map<String, String>? {
        val result = linkedMapOf<String, String>()
        body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return null
            val name = line.substring(0, separator).trim().lowercase()
            if (name in result) return null
            result[name] = line.substring(separator + 1).trim()
        }
        return result
    }

    private fun sameDescriptor(actual: String, expected: String): Boolean =
        actual.trim().split(Regex("\\s+")).zip(expected.split(Regex("\\s+"))).let { pairs ->
            actual.trim().split(Regex("\\s+")).size == expected.split(Regex("\\s+")).size &&
                pairs.all { (a, b) -> a.equals(b, true) }
        }

    private data class RtspMessage(
        val isResponse: Boolean, val method: String, val statusCode: Int,
        val cseq: String, val headers: Map<String, String>, val body: String
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
        return RtspMessage(isResponse, method, status, cseq, headers, raw.substring(boundary + 4))
    }

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
            } else matched = if (value == 13) 1 else 0
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
        private const val VIDEO_FORMAT = "00 00 01 01 00000001 00000000 00000000 00 0000 0000 00 none none"
        private val SUPPORTED_PARAMETERS = setOf(
            "wfd_video_formats", "wfd_audio_codecs", "wfd_presentation_url",
            "wfd_client_rtp_ports", "wfd_trigger_method", "wfd_standby", "wfd_idr_request"
        )
    }
}
