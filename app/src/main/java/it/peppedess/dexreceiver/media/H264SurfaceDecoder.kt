package it.peppedess.dexreceiver.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Surface-backed AVC decoder. Input must be Annex-B H.264 access units;
 * this class does not infer frame boundaries from arbitrary PES fragments.
 */
class H264SurfaceDecoder(
    private val surface: Surface,
    private val onLog: (String) -> Unit = {}
) {
    private var codec: MediaCodec? = null
    private val running = AtomicBoolean(false)
    private var presentationTimeUs = 0L

    @Synchronized
    fun start(width: Int = 1920, height: Int = 1080): Boolean {
        if (running.get()) return true
        return try {
            require(width in 16..4096 && height in 16..4096) { "Risoluzione AVC non valida" }
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            decoder.configure(format, surface, null, 0)
            decoder.start()
            codec = decoder
            running.set(true)
            onLog("Decoder AVC avviato: ${width}x${height}.")
            true
        } catch (t: Throwable) {
            running.set(false)
            try { codec?.release() } catch (_: Throwable) {}
            codec = null
            onLog("Avvio decoder AVC fallito: ${t.message}")
            false
        }
    }

    /** Queues one complete Annex-B access unit, not a raw TS/PES fragment. */
    fun queueAccessUnit(data: ByteArray, timeoutUs: Long = 10_000): Boolean {
        if (!running.get() || data.isEmpty()) return false
        val decoder = codec ?: return false
        return try {
            val inputIndex = decoder.dequeueInputBuffer(timeoutUs)
            if (inputIndex < 0) return false
            val input = decoder.getInputBuffer(inputIndex) ?: return false
            if (data.size > input.capacity()) {
                decoder.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
                onLog("Access unit AVC troppo grande: ${data.size} byte.")
                return false
            }
            input.clear()
            input.put(data)
            decoder.queueInputBuffer(inputIndex, 0, data.size, presentationTimeUs, 0)
            presentationTimeUs += 33_333
            drainOutput(decoder, timeoutUs)
            true
        } catch (t: Throwable) {
            onLog("Errore input decoder AVC: ${t.message}")
            false
        }
    }

    private fun drainOutput(decoder: MediaCodec, timeoutUs: Long) {
        val info = MediaCodec.BufferInfo()
        var index = decoder.dequeueOutputBuffer(info, timeoutUs)
        while (index >= 0) {
            decoder.releaseOutputBuffer(index, true)
            index = decoder.dequeueOutputBuffer(info, 0)
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
        onLog("Decoder AVC arrestato.")
    }

    fun isRunning(): Boolean = running.get()
}
