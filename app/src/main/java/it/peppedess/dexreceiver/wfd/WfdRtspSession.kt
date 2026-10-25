package it.peppedess.dexreceiver.wfd

/** RTSP session with a conservative Wi-Fi Display capability profile. */
class WfdRtspSession(
    private val mediaPort: Int = 19000,
    private val onStartMedia: (Int) -> Boolean = { false },
    private val onStopMedia: () -> Unit = {}
) {
    private var closed = false
    private var setup = false
    private var playing = false
    private var presentationUrl: String? = null
    private val sessionId = java.lang.Long.toHexString(System.nanoTime())

    fun handle(request: RtspRequest): String {
        if (closed) return response(454, "Session Not Found", request.cSeq)
        val cseq = request.cSeq ?: return response(400, "Bad Request", null)
        return when (request.method.uppercase()) {
            "OPTIONS" -> response(200, "OK", cseq, listOf("Public: org.wfa.wfd1.0, OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, PAUSE, TEARDOWN"))
            "GET_PARAMETER" -> handleGetParameter(request, cseq)
            "SET_PARAMETER" -> handleSetParameter(request, cseq)
            "SETUP" -> handleSetup(request, cseq)
            "PLAY" -> if (!setup) response(455, "Method Not Valid in This State", cseq) else {
                playing = true
                response(200, "OK", cseq, listOf("Session: $sessionId;timeout=60"))
            }
            "PAUSE" -> if (!setup) response(455, "Method Not Valid in This State", cseq) else {
                playing = false
                response(200, "OK", cseq, listOf("Session: $sessionId"))
            }
            "TEARDOWN" -> {
                onStopMedia()
                setup = false
                playing = false
                closed = true
                response(200, "OK", cseq, listOf("Session: $sessionId"))
            }
            else -> response(405, "Method Not Allowed", cseq, listOf("Allow: OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, PAUSE, TEARDOWN"))
        }
    }

    private fun handleGetParameter(request: RtspRequest, cseq: String): String {
        val requested = request.body.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() }.map { it.substringBefore(":").trim().lowercase() }.toSet()
        if (requested.isEmpty()) return response(200, "OK", cseq)
        val values = linkedMapOf(
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
        val unknown = requested.filterNot { it in values }
        if (unknown.isNotEmpty()) return response(451, "Parameter Not Understood", cseq)
        val body = requested.mapNotNull(values::get).joinToString("\r\n", postfix = "\r\n")
        return response(200, "OK", cseq, body = body)
    }

    private fun handleSetParameter(request: RtspRequest, cseq: String): String {
        val parameters = parseParameters(request.body)
            ?: return response(400, "Bad Request", cseq)
        val unknown = parameters.keys.firstOrNull { it !in SUPPORTED_SET_PARAMETERS }
        if (unknown != null) return response(451, "Parameter Not Understood", cseq)
        // Validate the complete request before mutating session state. A rejected
        // SET_PARAMETER must never leave half of an M4 negotiation committed.
        val selectedVideo = parameters["wfd_video_formats"]
        if (selectedVideo != null && !isSupportedVideoFormat(selectedVideo)) {
            return response(451, "Parameter Not Understood", cseq)
        }
        val selectedAudio = parameters["wfd_audio_codecs"]
        if (selectedAudio != null && !selectedAudio.equals("none", true)) {
            return response(451, "Parameter Not Understood", cseq)
        }
        val selectedUrl = parameters["wfd_presentation_url"]?.substringBefore(" none")?.trim()
        if (selectedUrl != null && (!selectedUrl.startsWith("rtsp://", true) || selectedUrl.any { it.isWhitespace() })) {
            return response(400, "Bad Request", cseq)
        }
        if (parameters["wfd_trigger_method"]?.uppercase() !in setOf(null, "SETUP", "PLAY", "PAUSE", "TEARDOWN", "IDR")) {
            return response(451, "Parameter Not Understood", cseq)
        }
        if (selectedUrl != null) presentationUrl = selectedUrl
        // M5 trigger is acknowledged; the source then opens the RTSP SETUP request.
        return response(200, "OK", cseq)
    }

    /** This build supports only the exact conservative descriptor it advertises. */
    private fun isSupportedVideoFormat(value: String): Boolean {
        val fields = value.trim().split(Regex("\\\\s+"))
        val advertised = VIDEO_FORMAT.split(Regex("\\\\s+"))
        return fields.size == advertised.size && fields.zip(advertised).all { (actual, expected) ->
            actual.equals(expected, ignoreCase = true)
        }
    }

    private fun parseParameters(body: String): Map<String, String>? {
        val result = linkedMapOf<String, String>()
        body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val separator = line.indexOf(":")
            if (separator <= 0) return null
            val name = line.substring(0, separator).trim().lowercase()
            if (name in result) return null
            result[name] = line.substring(separator + 1).trim()
        }
        return result
    }

    private fun handleSetup(request: RtspRequest, cseq: String): String {
        if (setup) return response(455, "Method Not Valid in This State", cseq)
        val transport = request.headers.entries.firstOrNull { it.key.equals("Transport", true) }?.value
            ?: return response(461, "Unsupported Transport", cseq)
        if (!transport.contains("RTP/AVP/UDP", true) || !transport.contains("unicast", true)) {
            return response(461, "Unsupported Transport", cseq)
        }
        val match = Regex("client_port\\s*=\\s*(\\d+)(?:-(\\d+))?", RegexOption.IGNORE_CASE).find(transport)
            ?: return response(461, "Unsupported Transport", cseq)
        val rtp = match.groupValues[1].toIntOrNull() ?: return response(461, "Unsupported Transport", cseq)
        val rtcp = match.groupValues[2].toIntOrNull() ?: (rtp + 1)
        if (rtp !in 1..65534 || rtcp !in 1..65535 || rtcp == rtp ||
            mediaPort !in 1024..65534 || mediaPort == 65535) {
            return response(461, "Unsupported Transport", cseq)
        }
        if (!onStartMedia(mediaPort)) return response(500, "Internal Server Error", cseq)
        setup = true
        val negotiated = "RTP/AVP/UDP;unicast;client_port=$rtp-$rtcp;server_port=$mediaPort-${mediaPort + 1};mode=play"
        return response(200, "OK", cseq, listOf("Session: $sessionId;timeout=60", "Transport: $negotiated"))
    }

    fun isClosed(): Boolean = closed
    fun isPlaying(): Boolean = playing
    fun getPresentationUrl(): String? = presentationUrl

    private fun response(
        code: Int, reason: String, cseq: String?, extra: List<String> = emptyList(), body: String = ""
    ): String = buildString {
        append("RTSP/1.0 ").append(code).append(" ").append(reason).append("\r\n")
        if (cseq != null) append("CSeq: ").append(cseq).append("\r\n")
        extra.forEach { append(it).append("\r\n") }
        if (body.isNotEmpty()) append("Content-Type: text/parameters\r\n")
        append("Content-Length: ").append(body.toByteArray(Charsets.UTF_8).size).append("\r\n\r\n")
        append(body)
    }

    companion object {
        // Conservative baseline capability descriptor from the WFD M3 example profile.
        private const val VIDEO_FORMAT = "00 00 01 01 00000001 00000000 00000000 00 0000 0000 00 none none"
        private val SUPPORTED_SET_PARAMETERS = setOf(
            "wfd_video_formats", "wfd_audio_codecs", "wfd_presentation_url",
            "wfd_client_rtp_ports", "wfd_trigger_method", "wfd_standby", "wfd_idr_request"
        )
    }
}
