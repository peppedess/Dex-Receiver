package it.peppedess.dexreceiver.wfd

/** Bounded RTSP/1.0 parser foundation. Parsing RTSP alone does not implement Miracast/WFD. */
data class RtspRequest(
    val method: String,
    val uri: String,
    val version: String,
    val headers: Map<String, String>,
    val body: String
) {
    val cSeq: String? get() = headers.entries.firstOrNull { it.key.equals("CSeq", true) }?.value
}

sealed class RtspParseResult {
    data class Request(val value: RtspRequest) : RtspParseResult()
    data class Error(val reason: String) : RtspParseResult()
}

object WfdRtspParser {
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_BODY_BYTES = 64 * 1024

    /** Parses exactly one complete RTSP message. */
    fun parse(message: String): RtspParseResult {
        val headerEnd = message.indexOf("\r\n\r\n")
        if (headerEnd < 0) return RtspParseResult.Error("Incomplete RTSP headers")
        val headerText = message.substring(0, headerEnd)
        if (headerText.toByteArray(Charsets.UTF_8).size > MAX_HEADER_BYTES) {
            return RtspParseResult.Error("RTSP headers too large")
        }

        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull()?.trim().orEmpty().split(Regex("\\s+"))
        if (requestLine.size != 3 || requestLine[0].isBlank() ||
            requestLine[1].isBlank() || requestLine[2] != "RTSP/1.0") {
            return RtspParseResult.Error("Invalid RTSP request line")
        }

        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            val separator = line.indexOf(':')
            if (separator <= 0) return RtspParseResult.Error("Malformed RTSP header")
            val name = line.substring(0, separator).trim()
            if (name.isEmpty() || headers.keys.any { it.equals(name, true) }) {
                return RtspParseResult.Error("Empty or duplicate RTSP header")
            }
            headers[name] = line.substring(separator + 1).trim()
        }

        val rawCSeq = headers.entries.firstOrNull { it.key.equals("CSeq", true) }?.value
            ?: return RtspParseResult.Error("Missing RTSP CSeq")
        if (rawCSeq.isEmpty() || rawCSeq.any { !it.isDigit() } || rawCSeq.toLongOrNull() == null) {
            return RtspParseResult.Error("Invalid RTSP CSeq")
        }

        val rawLength = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value
        val contentLength = if (rawLength == null) 0 else rawLength.toIntOrNull()
            ?: return RtspParseResult.Error("Invalid RTSP Content-Length")
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
            return RtspParseResult.Error("Invalid or excessive RTSP Content-Length")
        }
        val body = message.substring(headerEnd + 4)
        val bodyBytes = body.toByteArray(Charsets.UTF_8).size
        if (bodyBytes < contentLength) return RtspParseResult.Error("Incomplete RTSP body")
        if (bodyBytes > contentLength) return RtspParseResult.Error("Unexpected trailing RTSP data")
        return RtspParseResult.Request(
            RtspRequest(requestLine[0].uppercase(), requestLine[1], requestLine[2], headers.toMap(), body)
        )
    }
}
