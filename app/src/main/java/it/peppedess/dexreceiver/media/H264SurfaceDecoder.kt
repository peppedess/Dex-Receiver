package it.peppedess.dexreceiver.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/** Decodes complete Annex-B AVC access units and renders decoded frames to a Surface. */
class H264SurfaceDecoder(
    private val surface: Surface,
    private val onLog: (String) -> Unit = {}
) {
    private var codec: MediaCodec? = null
    private val running = AtomicBoolean(false)
    private var presentationTimeUs = 0L
    private var queuedFrames = 0L
    private var renderedFrames = 0L

    @Synchronized
    fun start(width: Int = 1920, height: Int = 1080): Boolean {
        if (running.get()) return true
        if (!surface.isValid) { onLog("Surface video non valida: decoder non avviato."); return false }
        return try {
            require(width in 16..4096 && height in 16..4096) { "Risoluzione AVC non valida" }
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { info -> !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                ?.name
            val decoder = if (codecName != null) MediaCodec.createByCodecName(codecName)
                else MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            decoder.configure(format, surface, null, 0)
            decoder.start()
            codec = decoder
            presentationTimeUs = 0L
            queuedFrames = 0L
            renderedFrames = 0L
            running.set(true)
            onLog("Decoder AVC pronto: ${width}x${height} · ${decoder.name}.")
            true
        } catch (t: Throwable) {
            running.set(false)
            try { codec?.release() } catch (_: Throwable) {}
            codec = null
            onLog("Avvio decoder AVC fallito: ${t.message}")
            false
        }
    }

    /** Queues one complete Annex-B access unit; called from the media receive worker. */
    @Synchronized
    fun queueAccessUnit(data: ByteArray, timeoutUs: Long = 10_000): Boolean {
        if (!running.get() || data.isEmpty() || !surface.isValid) return false
        val decoder = codec ?: return false
        return try {
            val inputIndex = decoder.dequeueInputBuffer(timeoutUs)
            if (inputIndex < 0) return false
            val input = decoder.getInputBuffer(inputIndex) ?: return false
            if (data.size > input.capacity()) {
                decoder.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
                onLog("Access unit AVC troppo grande (${data.size} byte; buffer ${input.capacity()}).")
                return false
            }
            input.clear()
            input.put(data)
            decoder.queueInputBuffer(inputIndex, 0, data.size, presentationTimeUs, 0)
            presentationTimeUs += 33_333
            queuedFrames++
            drainOutput(decoder, timeoutUs)
            if (queuedFrames == 1L || queuedFrames % 120L == 0L) {
                onLog("Decoder AVC: $queuedFrames access unit inviate · $renderedFrames frame renderizzati.")
            }
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
            if (info.size > 0) renderedFrames++
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
        onLog("Decoder AVC arrestato · $renderedFrames frame renderizzati.")
    }

    fun isRunning(): Boolean = running.get()
}
