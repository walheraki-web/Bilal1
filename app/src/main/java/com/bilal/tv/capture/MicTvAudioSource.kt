package com.bilal.tv.capture

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import com.bilal.tv.util.BLog
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Captures the TV sound through the receiver's microphone. Simplest source and
 * needs no ADB, but the receiver must have (or have attached) a working mic and
 * the room must be reasonably quiet. Timestamps come from the AudioRecord
 * BOOTTIME clock so they line up with the station playback clock.
 */
class MicTvAudioSource : TvAudioSource {

    override val label = "MIC 48 kHz"

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    override fun start(sink: TvAudioSource.Sink) {
        val rate = 48000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            rate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, rate), // ~1 s buffer
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IOException("Microphone not available")
        }
        record = rec
        running = true
        rec.startRecording()
        worker = thread(name = "bilal-mic", isDaemon = true) {
            val ts = AudioTimestamp()
            val block = 1920 // 20 ms stereo frames * 2 ch
            val buf = ShortArray(block * 2)
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
                        (framePos - ts.framePosition) * 1000 / 48000 + ts.nanoTime / 1_000_000
                    } else {
                        SystemClock.elapsedRealtime()
                    }
                    sink.onBlock(buf, buf.size, 2, 48000, firstTimeMs)
                    framePos += frames
                }
            } catch (e: Exception) {
                if (running) BLog.e("mic capture", e)
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
