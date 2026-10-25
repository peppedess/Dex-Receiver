package it.peppedess.dexreceiver.media

import java.io.ByteArrayOutputStream

/**
 * Reassembles Annex-B H.264 NAL units into access units.
 *
 * PES boundaries are not frame boundaries. This assembler uses AUD NALs when
 * present and first_mb_in_slice from VCL slice headers otherwise. It retains
 * SPS/PPS and prepends them to IDR access units when absent.
 */
class H264AccessUnitAssembler(
    private val onAccessUnit: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit = {}
) {
    private val stream = ByteArrayOutputStream()
    private val current = ByteArrayOutputStream()
    private var hasVcl = false
    private var hasSps = false
    private var hasPps = false
    private var sps = ByteArray(0)
    private var pps = ByteArray(0)

    @Synchronized
    fun consume(data: ByteArray) {
        if (data.isEmpty()) return
        stream.write(data)
        parseAvailable(false)
        if (stream.size() > MAX_BUFFER) {
            onLog("Buffer H.264 oltre il limite: reset della sequenza.")
            stream.reset()
            current.reset()
            hasVcl = false
        }
    }

    @Synchronized
    fun flush() {
        parseAvailable(true)
        emitCurrent()
        stream.reset()
    }

    @Synchronized
    fun reset() {
        stream.reset()
        current.reset()
        hasVcl = false
        hasSps = false
        hasPps = false
        sps = ByteArray(0)
        pps = ByteArray(0)
    }

    private fun parseAvailable(final: Boolean) {
        val bytes = stream.toByteArray()
        val starts = findStartCodes(bytes)
        if (starts.isEmpty()) {
            if (final && bytes.isNotEmpty()) onLog("Dati H.264 senza start code Annex-B.")
            return
        }
        if (starts[0].first > 0) {
            // Ignore leading transport padding/noise before the first start code.
        }
        val completeCount = if (final) starts.size else starts.size - 1
        if (completeCount <= 0) return
        for (index in 0 until completeCount) {
            val (start, prefix) = starts[index]
            val end = if (index + 1 < starts.size) starts[index + 1].first else bytes.size
            if (end <= start + prefix) continue
            val nal = bytes.copyOfRange(start + prefix, end)
            processNal(nal)
        }
        val consumed = if (final) bytes.size else starts[completeCount].first
        stream.reset()
        if (consumed < bytes.size) stream.write(bytes, consumed, bytes.size - consumed)
    }

    private fun processNal(nal: ByteArray) {
        if (nal.isEmpty()) return
        val type = nal[0].toInt() and 0x1f
        when (type) {
            9 -> { // Access Unit Delimiter
                if (hasVcl) emitCurrent()
                appendNal(nal)
            }
            7 -> {
                sps = annexB(nal)
                hasSps = true
                appendNal(nal)
            }
            8 -> {
                pps = annexB(nal)
                hasPps = true
                appendNal(nal)
            }
            in 1..5 -> {
                val firstMb = firstMbInSlice(nal)
                if (type == 5 && !hasSps && sps.isNotEmpty()) current.write(sps)
                if (type == 5 && !hasPps && pps.isNotEmpty()) current.write(pps)
                if (firstMb == 0 && hasVcl) emitCurrent()
                appendNal(nal)
                hasVcl = true
            }
            else -> appendNal(nal)
        }
    }

    private fun appendNal(nal: ByteArray) {
        current.write(START_CODE)
        current.write(nal)
    }

    private fun emitCurrent() {
        if (current.size() > 0 && hasVcl) onAccessUnit(current.toByteArray())
        current.reset()
        hasVcl = false
        hasSps = false
        hasPps = false
    }

    private fun firstMbInSlice(nal: ByteArray): Int? {
        if (nal.size < 2) return null
        val rbsp = removeEmulationPrevention(nal.copyOfRange(1, nal.size))
        val bits = BitReader(rbsp)
        return bits.readUnsignedExpGolomb()
    }

    private fun removeEmulationPrevention(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size)
        var zeros = 0
        input.forEach { byte ->
            val value = byte.toInt() and 0xff
            if (zeros >= 2 && value == 3) {
                zeros = 0
            } else {
                out.write(value)
                zeros = if (value == 0) zeros + 1 else 0
            }
        }
        return out.toByteArray()
    }

    private fun findStartCodes(data: ByteArray): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    result += i to 3
                    i += 3
                    continue
                }
                if (i + 4 <= data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    result += i to 4
                    i += 4
                    continue
                }
            }
            i++
        }
        return result
    }

    private fun annexB(nal: ByteArray): ByteArray = START_CODE + nal

    private class BitReader(private val data: ByteArray) {
        private var bit = 0
        fun readUnsignedExpGolomb(): Int? {
            var zeros = 0
            while (bit < data.size * 8 && readBit() == 0) {
                zeros++
                if (zeros > 30) return null
            }
            if (bit > data.size * 8) return null
            var suffix = 0
            repeat(zeros) { suffix = (suffix shl 1) or (readBit() ?: return null) }
            return ((1 shl zeros) - 1) + suffix
        }
        private fun readBit(): Int? {
            if (bit >= data.size * 8) return null
            val value = (data[bit / 8].toInt() ushr (7 - bit % 8)) and 1
            bit++
            return value
        }
    }

    companion object {
        private const val MAX_BUFFER = 4 * 1024 * 1024
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
