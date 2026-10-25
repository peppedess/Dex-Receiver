package it.peppedess.dexreceiver.wfd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WfdRtspParserTest {
    @Test fun parsesOptionsRequest() {
        val raw = "OPTIONS * RTSP/1.0\r\nCSeq: 7\r\n\r\n"
        val result = WfdRtspParser.parse(raw)
        assertTrue(result is RtspParseResult.Request)
        val request = (result as RtspParseResult.Request).value
        assertEquals("OPTIONS", request.method)
        assertEquals("7", request.cSeq)
        assertEquals("*", request.uri)
    }

    @Test fun parsesExactBodyLength() {
        val raw = "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 8\r\nContent-Length: 4\r\n\r\ntest"
        val result = WfdRtspParser.parse(raw)
        assertTrue(result is RtspParseResult.Request)
        assertEquals("test", (result as RtspParseResult.Request).value.body)
    }

    @Test fun rejectsIncompleteBody() {
        val raw = "SET_PARAMETER * RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 5\r\n\r\ntest"
        assertTrue(WfdRtspParser.parse(raw) is RtspParseResult.Error)
    }

    @Test fun rejectsTrailingBytes() {
        val raw = "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\nextra"
        assertTrue(WfdRtspParser.parse(raw) is RtspParseResult.Error)
    }

    @Test fun rejectsNonNumericCSeq() {
        val raw = "OPTIONS * RTSP/1.0\\r\\nCSeq: abc\\r\\n\\r\\n"
        assertTrue(WfdRtspParser.parse(raw) is RtspParseResult.Error)
    }

    @Test fun rejectsOverflowingCSeq() {
        val raw = "OPTIONS * RTSP/1.0\\r\\nCSeq: 999999999999999999999999\\r\\n\\r\\n"
        assertTrue(WfdRtspParser.parse(raw) is RtspParseResult.Error)
    }

    @Test fun rejectsMalformedContentLength() {
        val raw = "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nContent-Length: nope\r\n\r\n"
        assertTrue(WfdRtspParser.parse(raw) is RtspParseResult.Error)
    }
}
