package it.peppedess.dexreceiver.wfd

/**
 * Conservative RTSP control-session skeleton.
 *
 * It handles generic RTSP control verbs but deliberately does not advertise
 * WFD capabilities or accept SETUP/PLAY. Those require a complete negotiated
 * Wi-Fi Display profile and a media transport implementation.
 */
class WfdRtspSession {
    private var closed = false

    fun handle(request: RtspRequest): String {
        if (closed) return response(454, "Session Not Found", request.cSeq)
        val cseq = request.cSeq
        if (cseq.isNullOrBlank()) return response(400, "Bad Request", null)

        return when (request.method.uppercase()) {
            "OPTIONS" -> response(
                200,
                "OK",
                cseq,
                extraHeaders = listOf("Public: OPTIONS, GET_PARAMETER, TEARDOWN")
            )
            "GET_PARAMETER" -> response(200, "OK", cseq)
            "SET_PARAMETER" -> {
                // Parameters are intentionally not acknowledged as negotiated WFD
                // features until their syntax and semantics are implemented.
                response(501, "Not Implemented", cseq)
            }
            "SETUP", "PLAY" -> response(501, "Not Implemented", cseq)
            "TEARDOWN" -> {
                closed = true
                response(200, "OK", cseq)
            }
            else -> response(405, "Method Not Allowed", cseq,
                extraHeaders = listOf("Allow: OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, TEARDOWN"))
        }
    }

    fun isClosed(): Boolean = closed

    private fun response(
        code: Int,
        reason: String,
        cseq: String?,
        extraHeaders: List<String> = emptyList()
    ): String = buildString {
        append("RTSP/1.0 ").append(code).append(' ').append(reason).append("\r\n")
        if (cseq != null) append("CSeq: ").append(cseq).append("\r\n")
        extraHeaders.forEach { append(it).append("\r\n") }
        append("Content-Length: 0\r\n\r\n")
    }
}
