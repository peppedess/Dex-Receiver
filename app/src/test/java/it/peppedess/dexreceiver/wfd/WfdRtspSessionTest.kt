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
        assertTrue(response.contains("Public: org.wfa.wfd1.0, OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, PAUSE, TEARDOWN"))
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
    fun getParameterReturnsRequestedWfdCapabilitiesWithCorrectLength() {
        val body = "wfd_video_formats\r\nwfd_audio_codecs\r\nwfd_client_rtp_ports\r\n"
        val response = WfdRtspSession(mediaPort = 19000).handle(
            request("GET_PARAMETER", "8", body = body)
        )
        assertTrue(response.startsWith("RTSP/1.0 200 OK"))
        assertTrue(response.contains("wfd_video_formats:"))
        assertTrue(response.contains("wfd_audio_codecs: none"))
        assertTrue(response.contains("wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play"))
        val declared = Regex("Content-Length: (\\d+)").find(response)!!.groupValues[1].toInt()
        val responseBody = response.substringAfter("\r\n\r\n")
        assertEquals(declared, responseBody.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun setParameterAcceptsWfdM4AndM5Parameters() {
        val session = WfdRtspSession()
        val m4 = "wfd_video_formats: 00 00 01 01 00000001 00000000 00000000 00 0000 0000 00 none none\r\n" +
            "wfd_audio_codecs: none\r\n" +
            "wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0/streamid=0 none\r\n"
        assertTrue(session.handle(request("SET_PARAMETER", "9", body = m4)).startsWith("RTSP/1.0 200 OK"))
        assertEquals("rtsp://192.168.49.1/wfd1.0/streamid=0", session.getPresentationUrl())
        assertTrue(session.handle(request("SET_PARAMETER", "10", body = "wfd_trigger_method: SETUP\r\n")).startsWith("RTSP/1.0 200 OK"))
    }

    @Test
    fun unsupportedSelectedAudioCodecIsRejectedRatherThanFalselyAccepted() {
        val response = WfdRtspSession().handle(
            request("SET_PARAMETER", "11", body = "wfd_audio_codecs: LPCM 00000003 00\r\n")
        )
        assertTrue(response.startsWith("RTSP/1.0 451"))
    }

    @Test
    fun rejectedSetParameterDoesNotPartiallyCommitPresentationUrl() {
        val session = WfdRtspSession()
        val body = "wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0/streamid=0 none\r\n" +
            "wfd_audio_codecs: LPCM 00000003 00\r\n"
        assertTrue(session.handle(request("SET_PARAMETER", "12", body = body)).startsWith("RTSP/1.0 451"))
        assertEquals(null, session.getPresentationUrl())
    }

    @Test
    fun setupRejectsDuplicateSetupAndInvalidPortPair() {
        var starts = 0
        val session = WfdRtspSession(onStartMedia = { starts++; true })
        val headers = mapOf("Transport" to "RTP/AVP/UDP;unicast;client_port=5000-5001")
        assertTrue(session.handle(request("SETUP", "13", headers)).startsWith("RTSP/1.0 200"))
        assertTrue(session.handle(request("SETUP", "14", headers)).startsWith("RTSP/1.0 455"))
        assertEquals(1, starts)
        val invalid = WfdRtspSession(onStartMedia = { true }).handle(request(
            "SETUP", "15", mapOf("Transport" to "RTP/AVP/UDP;unicast;client_port=5000-5000")
        ))
        assertTrue(invalid.startsWith("RTSP/1.0 461"))
    }

    @Test
    fun missingCSeqIsRejected() {
        val response = WfdRtspSession().handle(request("OPTIONS", null))
        assertTrue(response.startsWith("RTSP/1.0 400"))
        assertFalse(response.contains("CSeq:"))
    }
    @Test
    fun setParameterRejectsVideoDescriptorThatWasNotAdvertised() {
        val session = WfdRtspSession()
        val unsupported = "wfd_video_formats: 00 00 01 01 00000002 00000000 00000000 00 0000 0000 00 none none\r\n"
        assertTrue(session.handle(request("SET_PARAMETER", "16", body = unsupported)).startsWith("RTSP/1.0 451"))
    }

    @Test
    fun setParameterAcceptsExactAdvertisedVideoDescriptor() {
        val session = WfdRtspSession()
        val exact = "wfd_video_formats: 00 00 01 01 00000001 00000000 00000000 00 0000 0000 00 none none\r\n"
        assertTrue(session.handle(request("SET_PARAMETER", "17", body = exact)).startsWith("RTSP/1.0 200"))
    }

}

    @Test
    fun emptyGetParameterReturnsCompleteWfdCapabilitySet() {
        val response = WfdRtspSession().handle(request("GET_PARAMETER", "18"))
        assertTrue(response.startsWith("RTSP/1.0 200 OK"))
        assertTrue(response.contains("wfd_video_formats:"))
        assertTrue(response.contains("wfd_audio_codecs: none"))
        assertTrue(response.contains("wfd_client_rtp_ports:"))
        assertTrue(response.contains("wfd_idr_request_capability: 1"))
        val declared = Regex("Content-Length: (\\d+)").find(response)!!.groupValues[1].toInt()
        val body = response.substringAfter("\r\n\r\n")
        assertEquals(declared, body.toByteArray(Charsets.UTF_8).size)
    }
