package com.bilal.tv.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import com.bilal.tv.util.BLog
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Captures the receiver's own audio output in-process, with no Shizuku and no
 * ADB — relying on the box's vendor permission manager having granted
 * CAPTURE_AUDIO_OUTPUT. Uses the REMOTE_SUBMIX source, which on these boxes
 * carries the live channel's sound.
 *
 * If the permission isn't granted the AudioRecord fails to initialise and this
 * throws, so the caller can fall back to Shizuku or the mic.
 */
class DirectCaptureSource : TvAudioSource {

    override val label = "ECHO مباشر 48kHz"

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    override fun start(sink: TvAudioSource.Sink) {
        val rate = 48000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.REMOTE_SUBMIX,
            rate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, rate * 2 * 2),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IOException("التقاط الخرج غير مسموح — امنح صلاحية التقاط الصوت من مدير صلاحيات الصندوق")
        }
        record = rec
        running = true
        rec.startRecording()
        BLog.i("direct REMOTE_SUBMIX capture started")
        worker = thread(name = "bilal-echo-direct", isDaemon = true) {
            val ts = AudioTimestamp()
            val buf = ShortArray(1920 * 2)
            var framePos = 0L
            try {
                while (running) {
                    var off = 0
                    while (off < buf.size && running) {
                        val r = rec.read(buf, off, buf.size - off)
                        if (r <= 0) throw IOException("AudioRecord read $r")
                        off += r
                    }
                    if (!running) break
                    val frames = buf.size / 2
                    val firstTimeMs = if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                        (framePos - ts.framePosition) * 1000 / rate + ts.nanoTime / 1_000_000
                    } else {
                        SystemClock.elapsedRealtime()
                    }
                    sink.onBlock(buf, buf.size, 2, rate, firstTimeMs)
                    framePos += frames
                }
            } catch (e: Exception) {
                if (running) BLog.e("direct capture", e)
            }
        }
    }

    override fun stop() {
        running = false
        worker?.join(1000)
        worker = null
        record?.runCatching { stop() }
        record?.release()
        record = null
    }
}
