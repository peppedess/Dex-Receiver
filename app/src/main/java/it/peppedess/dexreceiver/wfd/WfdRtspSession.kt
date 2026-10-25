package it.peppedess.dexreceiver.wfd

/** RTSP control session for the UDP RTP profile implemented by this prototype. */
class WfdRtspSession(
    private val mediaPort: Int = 19000,
    private val onStartMedia: (Int) -> Boolean = { false },
    private val onStopMedia: () -> Unit = {}
) {
    private var closed = false
    private var setup = false
    private var playing = false
    private val sessionId = java.lang.Long.toHexString(System.nanoTime())

    fun handle(request: RtspRequest): String {
        if (closed) return response(454, "Session Not Found", request.cSeq)
        val cseq = request.cSeq ?: return response(400, "Bad Request", null)
        return when (request.method.uppercase()) {
            "OPTIONS" -> response(200, "OK", cseq, listOf("Public: OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, TEARDOWN"))
            "GET_PARAMETER" -> response(200, "OK", cseq)
            "SET_PARAMETER" -> response(200, "OK", cseq)
            "SETUP" -> handleSetup(request, cseq)
            "PLAY" -> if (!setup) response(455, "Method Not Valid in This State", cseq) else {
                playing = true
                response(200, "OK", cseq, listOf("Session: $sessionId"))
            }
            "TEARDOWN" -> {
                onStopMedia()
                closed = true
                response(200, "OK", cseq, listOf("Session: $sessionId"))
            }
            else -> response(405, "Method Not Allowed", cseq, listOf("Allow: OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, TEARDOWN"))
        }
    }

    private fun handleSetup(request: RtspRequest, cseq: String): String {
        val transport = request.headers.entries.firstOrNull { it.key.equals("Transport", true) }?.value
            ?: return response(461, "Unsupported Transport", cseq)
        if (!transport.contains("RTP/AVP/UDP", true) || !transport.contains("unicast", true)) {
            return response(461, "Unsupported Transport", cseq)
        }
        val match = Regex("client_port\\s*=\\s*(\\d+)(?:-(\\d+))?", RegexOption.IGNORE_CASE).find(transport)
            ?: return response(461, "Unsupported Transport", cseq)
        val rtp = match.groupValues[1].toIntOrNull() ?: return response(461, "Unsupported Transport", cseq)
        val rtcp = match.groupValues[2].toIntOrNull() ?: (rtp + 1)
        if (rtp !in 1..65534 || rtcp !in 1..65535) return response(461, "Unsupported Transport", cseq)
        if (!onStartMedia(mediaPort)) return response(500, "Internal Server Error", cseq)
        setup = true
        val negotiated = "RTP/AVP/UDP;unicast;client_port=$rtp-$rtcp;server_port=$mediaPort-${mediaPort + 1};mode=play"
        return response(200, "OK", cseq, listOf("Session: $sessionId", "Transport: $negotiated"))
    }

    fun isClosed(): Boolean = closed
    fun isPlaying(): Boolean = playing

    private fun response(code: Int, reason: String, cseq: String?, extra: List<String> = emptyList()): String = buildString {
        append("RTSP/1.0 ").append(code).append(' ').append(reason).append("\r\n")
        if (cseq != null) append("CSeq: ").append(cseq).append("\r\n")
        extra.forEach { append(it).append("\r\n") }
        append("Content-Length: 0\r\n\r\n")
    }
}
