package it.peppedess.dexreceiver.wfd

import java.io.ByteArrayOutputStream

/**
 * MPEG-TS demuxer with per-PID continuity tracking. Damaged PSI/PES fragments
 * are discarded and video continuation is ignored until the next PES start.
 */
class MpegTsDemuxer(
    private val onVideoPes: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit = {},
    private val onVideoDiscontinuity: () -> Unit = {}
) {
    private var pmtPid: Int? = null
    private var videoPid: Int? = null
    private val lastContinuityByPid = HashMap<Int, Int>()
    private val sectionBuffers = HashMap<Int, ByteArrayOutputStream>()
    private val awaitingPsiStart = HashSet<Int>()
    private val pesBuffer = ByteArrayOutputStream()
    private var awaitingVideoStart = true

    fun reset() {
        pmtPid = null
        videoPid = null
        lastContinuityByPid.clear()
        sectionBuffers.clear()
        awaitingPsiStart.clear()
        pesBuffer.reset()
        awaitingVideoStart = true
    }

    /** Drops in-flight video data after transport framing loss, preserving PAT/PMT. */
    fun resetVideoStream() {
        pesBuffer.reset()
        awaitingVideoStart = true
        onVideoDiscontinuity()
    }

    fun consume(packet: MpegTsPacket) {
        if (packet.discontinuity) {
            lastContinuityByPid.remove(packet.pid)
            discardPidState(packet.pid)
        }
        if (!packet.hasPayload || packet.payload.isEmpty()) return

        val previous = lastContinuityByPid[packet.pid]
        if (previous != null) {
            val expected = (previous + 1) and 0x0f
            if (packet.continuityCounter == previous) {
                onLog("Pacchetto MPEG-TS duplicato ignorato sul PID ${packet.pid}.")
                return
            }
            if (packet.continuityCounter != expected) {
                onLog("Discontinuità MPEG-TS sul PID ${packet.pid}: atteso $expected, ricevuto ${packet.continuityCounter}.")
                discardPidState(packet.pid)
            }
        }
        lastContinuityByPid[packet.pid] = packet.continuityCounter

        if (packet.pid == 0 || packet.pid == pmtPid) {
            consumePsi(packet.pid, packet.payloadUnitStart, packet.payload)
        } else if (packet.pid == videoPid) {
            if (awaitingVideoStart && !packet.payloadUnitStart) return
            if (packet.payloadUnitStart) awaitingVideoStart = false
            consumePes(packet.payloadUnitStart, packet.payload)
        }
    }

    fun flush() {
        if (pesBuffer.size() > 0 && !awaitingVideoStart) {
            onVideoPes(pesBuffer.toByteArray())
            pesBuffer.reset()
        }
    }

    private fun discardPidState(pid: Int) {
        sectionBuffers.remove(pid)
        if (pid == 0 || pid == pmtPid) awaitingPsiStart.add(pid)
        if (pid == videoPid) {
            pesBuffer.reset()
            awaitingVideoStart = true
            onVideoDiscontinuity()
        }
    }

    private fun consumePsi(pid: Int, start: Boolean, data: ByteArray) {
        if (pid in awaitingPsiStart && !start) return
        var offset = 0
        if (start) {
            awaitingPsiStart.remove(pid)
            if (data.isEmpty()) return
            val pointer = data[0].toInt() and 0xff
            offset = 1 + pointer
            if (offset > data.size) return
            sectionBuffers.remove(pid)
        }
        val buffer = sectionBuffers.getOrPut(pid) { ByteArrayOutputStream() }
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
                awaitingVideoStart = true
                return
            }
            i += 5 + infoLength
        }
        onLog("PMT ricevuta, ma non contiene una traccia H.264.")
    }

    private fun consumePes(start: Boolean, data: ByteArray) {
        if (start) {
            flush()
            if (data.size < 9 || data[0].toInt() != 0 || data[1].toInt() != 0 || (data[2].toInt() and 0xff) != 1) {
                onLog("Header PES video non valido.")
                awaitingVideoStart = true
                return
            }
            val payloadOffset = 9 + (data[8].toInt() and 0xff)
            if (payloadOffset > data.size) {
                onLog("Header PES video incompleto.")
                awaitingVideoStart = true
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
