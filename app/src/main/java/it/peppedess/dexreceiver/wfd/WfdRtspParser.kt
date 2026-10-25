package it.peppedess.dexreceiver.wfd

/** Bounded RTSP/1.0 parser foundation. Parsing RTSP alone does not implement Miracast/WFD. */
data class RtspRequest(val method: String, val uri: String, val version: String, val headers: Map<String, String>, val body: String) {
    val cSeq: String? get() = headers.entries.firstOrNull { it.key.equals("CSeq", true) }?.value
}

sealed class RtspParseResult {
    data class Request(val value: RtspRequest) : RtspParseResult()
    data class Error(val reason: String) : RtspParseResult()
}

object WfdRtspParser {
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_BODY_BYTES = 64 * 1024

    /** Parses one complete RTSP message received from the control socket. */
    fun parse(message: String): RtspParseResult {
        val headerEnd = message.indexOf("\r\n\r\n")
        if (headerEnd < 0) return RtspParseResult.Error("Incomplete RTSP headers")
        if (headerEnd > MAX_HEADER_BYTES) return RtspParseResult.Error("RTSP headers too large")

        val lines = message.substring(0, headerEnd).split("\r\n")
        val requestLine = lines.firstOrNull()?.trim().orEmpty().split(Regex("\\s+"))
        if (requestLine.size != 3 || !requestLine[2].startsWith("RTSP/")) {
            return RtspParseResult.Error("Invalid RTSP request line")
        }

        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            val separator = line.indexOf(':')
            if (separator <= 0) return RtspParseResult.Error("Malformed RTSP header")
            val name = line.substring(0, separator).trim()
            if (name.isEmpty()) return RtspParseResult.Error("Empty RTSP header name")
            headers[name] = line.substring(separator + 1).trim()
        }

        val contentLength = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }
            ?.value?.toIntOrNull() ?: 0
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
            return RtspParseResult.Error("Invalid or excessive RTSP Content-Length")
        }
        val body = message.substring(headerEnd + 4)
        if (body.toByteArray(Charsets.UTF_8).size < contentLength) {
            return RtspParseResult.Error("Incomplete RTSP body")
        }
        return RtspParseResult.Request(RtspRequest(
            method = requestLine[0].uppercase(), uri = requestLine[1], version = requestLine[2],
            headers = headers.toMap(), body = body
        ))
    }
}
