package it.peppedess.dexreceiver.wfd

/**
 * Small MPEG-TS demuxer for the common Miracast MPEG-2 TS transport.
 * Discovers the H.264 elementary PID through PAT/PMT and emits PES payloads.
 * It deliberately rejects malformed sections instead of guessing stream IDs.
 */
class MpegTsDemuxer(
    private val onVideoPes: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit = {}
) {
    private var pmtPid: Int? = null
    private var videoPid: Int? = null
    private var continuity: Int? = null
    private val sectionBuffers = HashMap<Int, java.io.ByteArrayOutputStream>()
    private val pesBuffer = java.io.ByteArrayOutputStream()

    fun reset() {
        pmtPid = null
        videoPid = null
        continuity = null
        sectionBuffers.clear()
        pesBuffer.reset()
    }

    fun consume(packet: MpegTsPacket) {
        if (!packet.hasPayload || packet.payload.isEmpty()) return
        if (packet.pid == 0 || packet.pid == pmtPid) {
            consumePsi(packet.pid, packet.payloadUnitStart, packet.payload)
        } else if (packet.pid == videoPid) {
            consumePes(packet.payloadUnitStart, packet.payload)
        }
    }

    fun flush() {
        if (pesBuffer.size() > 0) {
            onVideoPes(pesBuffer.toByteArray())
            pesBuffer.reset()
        }
    }

    private fun consumePsi(pid: Int, start: Boolean, data: ByteArray) {
        var offset = 0
        if (start) {
            if (data.isEmpty()) return
            val pointer = data[0].toInt() and 0xff
            offset = 1 + pointer
            if (offset > data.size) return
            sectionBuffers.remove(pid)
        }
        val buffer = sectionBuffers.getOrPut(pid) { java.io.ByteArrayOutputStream() }
        buffer.write(data, offset, data.size - offset)
        val bytes = buffer.toByteArray()
        if (bytes.size < 3) return
        val sectionLength = ((bytes[1].toInt() and 0x0f) shl 8) or (bytes[2].toInt() and 0xff)
        val total = 3 + sectionLength
        if (total !in 8..4096 || bytes.size < total) return
        val section = bytes.copyOfRange(0, total)
        sectionBuffers.remove(pid)
        when (pid) {
            0 -> parsePat(section)
            pmtPid -> parsePmt(section)
        }
    }

    private fun parsePat(section: ByteArray) {
        if ((section[0].toInt() and 0xff) != 0x00) return
        var i = 8
        val end = section.size - 4
        while (i + 4 <= end) {
            val program = u16(section, i)
            val pid = ((section[i + 2].toInt() and 0x1f) shl 8) or (section[i + 3].toInt() and 0xff)
            if (program != 0) {
                pmtPid = pid
                onLog("PMT individuata sul PID $pid.")
                return
            }
            i += 4
        }
    }

    private fun parsePmt(section: ByteArray) {
        if ((section[0].toInt() and 0xff) != 0x02 || section.size < 16) return
        val programInfoLength = ((section[10].toInt() and 0x0f) shl 8) or (section[11].toInt() and 0xff)
        var i = 12 + programInfoLength
        val end = section.size - 4
        while (i + 5 <= end) {
            val streamType = section[i].toInt() and 0xff
            val pid = ((section[i + 1].toInt() and 0x1f) shl 8) or (section[i + 2].toInt() and 0xff)
            val infoLength = ((section[i + 3].toInt() and 0x0f) shl 8) or (section[i + 4].toInt() and 0xff)
            if (streamType == 0x1b) {
                if (videoPid != pid) onLog("Traccia H.264 individuata sul PID $pid.")
                videoPid = pid
                return
            }
            i += 5 + infoLength
        }
        onLog("PMT ricevuta, ma non contiene una traccia H.264.")
    }

    private fun consumePes(start: Boolean, data: ByteArray) {
        if (start) {
            flush()
            if (data.size < 9 || (data[0].toInt() and 0xff) != 0 || (data[1].toInt() and 0xff) != 0 || (data[2].toInt() and 0xff) != 1) {
                onLog("Header PES video non valido.")
                return
            }
            val headerLength = data[8].toInt() and 0xff
            val payloadOffset = 9 + headerLength
            if (payloadOffset > data.size) {
                onLog("Header PES video incompleto.")
                return
            }
            pesBuffer.write(data, payloadOffset, data.size - payloadOffset)
        } else if (pesBuffer.size() > 0) {
            pesBuffer.write(data)
        }
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
}
