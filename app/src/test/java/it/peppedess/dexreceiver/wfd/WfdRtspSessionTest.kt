package it.peppedess.dexreceiver.wfd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WfdRtspSessionTest {
    private fun request(method: String, cSeq: String? = "1") = RtspRequest(
        method = method,
        uri = "rtsp://127.0.0.1/wfd1.0",
        version = "RTSP/1.0",
        headers = if (cSeq == null) emptyMap() else mapOf("CSeq" to cSeq),
        body = ""
    )

    @Test
    fun optionsReturnsOnlyImplementedMethods() {
        val response = WfdRtspSession().handle(request("OPTIONS", "7"))
        assertTrue(response.startsWith("RTSP/1.0 200 OK"))
        assertTrue(response.contains("CSeq: 7"))
        assertTrue(response.contains("Public: OPTIONS, GET_PARAMETER, TEARDOWN"))
        assertFalse(response.contains("Public: OPTIONS, SETUP"))
    }

    @Test
    fun setupAndPlayAreNotFalselyAccepted() {
        val session = WfdRtspSession()
        assertTrue(session.handle(request("SETUP")).startsWith("RTSP/1.0 501"))
        assertTrue(session.handle(request("PLAY", "2")).startsWith("RTSP/1.0 501"))
        assertFalse(session.isClosed())
    }

    @Test
    fun missingCSeqIsRejected() {
        val response = WfdRtspSession().handle(request("OPTIONS", null))
        assertTrue(response.startsWith("RTSP/1.0 400"))
        assertFalse(response.contains("CSeq:"))
    }

    @Test
    fun teardownClosesSessionAndLaterRequestsFail() {
        val session = WfdRtspSession()
        assertTrue(session.handle(request("TEARDOWN")).startsWith("RTSP/1.0 200"))
        assertTrue(session.isClosed())
        assertTrue(session.handle(request("OPTIONS", "2")).startsWith("RTSP/1.0 454"))
    }
}
