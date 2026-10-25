package it.peppedess.dexreceiver.wfd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WfdRtspSessionTest {
    private fun request(
        method: String,
        cSeq: String? = "1",
        headers: Map<String, String> = emptyMap(),
        body: String = ""
    ) = RtspRequest(
        method = method,
        uri = "rtsp://127.0.0.1/wfd1.0",
        version = "RTSP/1.0",
        headers = if (cSeq == null) headers else headers + ("CSeq" to cSeq),
        body = body
    )

    @Test
    fun optionsListsImplementedRtspMethods() {
        val response = WfdRtspSession().handle(request("OPTIONS", "7"))
        assertTrue(response.startsWith("RTSP/1.0 200 OK"))
        assertTrue(response.contains("CSeq: 7"))
        assertTrue(response.contains("Public: OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, TEARDOWN"))
    }

    @Test
    fun setupNegotiatesUdpPortsAndPlayRequiresSetup() {
        var startedPort = -1
        val session = WfdRtspSession(mediaPort = 19000, onStartMedia = { startedPort = it; true })
        assertTrue(session.handle(request("PLAY", "1")).startsWith("RTSP/1.0 455"))
        val response = session.handle(request(
            "SETUP", "2",
            mapOf("Transport" to "RTP/AVP/UDP;unicast;client_port=5000-5001")
        ))
        assertTrue(response.startsWith("RTSP/1.0 200 OK"))
        assertTrue(response.contains("server_port=19000-19001"))
        assertEquals(19000, startedPort)
        assertTrue(session.handle(request("PLAY", "3")).startsWith("RTSP/1.0 200 OK"))
        assertTrue(session.isPlaying())
    }

    @Test
    fun unsupportedTransportIsRejected() {
        val session = WfdRtspSession(onStartMedia = { true })
        assertTrue(session.handle(request(
            "SETUP", "1",
            mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1")
        )).startsWith("RTSP/1.0 461"))
    }

    @Test
    fun teardownStopsMediaAndClosesSession() {
        var stopped = false
        val session = WfdRtspSession(onStopMedia = { stopped = true })
        assertTrue(session.handle(request("TEARDOWN")).startsWith("RTSP/1.0 200"))
        assertTrue(stopped)
        assertTrue(session.isClosed())
        assertTrue(session.handle(request("OPTIONS", "2")).startsWith("RTSP/1.0 454"))
    }

    @Test
    fun missingCSeqIsRejected() {
        val response = WfdRtspSession().handle(request("OPTIONS", null))
        assertTrue(response.startsWith("RTSP/1.0 400"))
        assertFalse(response.contains("CSeq:"))
    }
}
