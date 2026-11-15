package com.bilal.tv.audio

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * Wraps the real audio sink to tap the station PCM exactly as it is handed to the
 * hardware, with an accurate "will be heard at" wall-clock time. This is the
 * timeline the sync engine compares against the TV.
 *
 * Heard-time is derived from the sink's own playback position, so it already
 * accounts for the output buffer. It is correct for live HLS too, because it
 * never relies on the player's media position.
 */
@UnstableApi
class TappingAudioSink(
    sink: AudioSink,
    /** Called with (pcm copy, channels, sampleRate, firstFrameHeardMs). */
    private val onTap: (ByteBuffer, Int, Int, Long) -> Unit,
) : ForwardingAudioSink(sink) {

    @Volatile var tapping = false

    private var sampleRate = 0
    private var channels = 0
    private var bytesPerFrame = 0
    private var positionInBuffer = 0L // bytes already consumed within the current presentation unit
    private var lastPts = Long.MIN_VALUE

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        sampleRate = inputFormat.sampleRate
        channels = inputFormat.channelCount
        bytesPerFrame = channels * 2
        positionInBuffer = 0
        lastPts = Long.MIN_VALUE
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        // ExoPlayer may hand the same presentation unit over several calls; reset
        // the within-unit offset only when the timestamp actually advances.
        if (presentationTimeUs != lastPts) {
            lastPts = presentationTimeUs
            positionInBuffer = 0
        }
        val startPos = buffer.position()
        val wantTap = tapping && bytesPerFrame > 0 && sampleRate > 0
        val dup = if (wantTap) buffer.duplicate() else null
        val wallNow = if (wantTap) SystemClock.elapsedRealtime() else 0L
        val playedUs = if (wantTap) getCurrentPositionUs(false) else C.TIME_UNSET

        val accepted = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val consumed = buffer.position() - startPos

        if (wantTap && consumed > 0 && playedUs != C.TIME_UNSET && playedUs != Long.MIN_VALUE) {
            // How far ahead of the playhead the first consumed frame is.
            val aheadUs = positionInBuffer / bytesPerFrame * 1_000_000 / sampleRate +
                presentationTimeUs - playedUs
            if (aheadUs in -150_000..5_000_000) {
                dup!!.limit(startPos + consumed)
                dup.position(startPos)
                val copy = ByteBuffer.allocate(consumed)
                copy.put(dup)
                copy.flip()
                onTap(copy, channels, sampleRate, wallNow + aheadUs / 1000)
            }
        }
        positionInBuffer += consumed
        return accepted
    }

    override fun flush() {
        positionInBuffer = 0
        lastPts = Long.MIN_VALUE
        super.flush()
    }
}
