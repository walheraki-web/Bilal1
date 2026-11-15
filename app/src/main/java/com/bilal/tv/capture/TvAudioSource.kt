package com.bilal.tv.capture

/**
 * A source of the TV channel's audio, delivered as timestamped PCM blocks.
 *
 * Bilal syncs the internet station to whatever this source reports, so the one
 * requirement is an accurate timestamp: [onBlock]'s `firstFrameTimeMs` must be
 * the `SystemClock.elapsedRealtime()` moment at which the first sample of the
 * block is (or was) actually heard from the TV. Everything else — sample rate,
 * channel count, gain, codec — the sync engine tolerates.
 */
interface TvAudioSource {

    fun interface Sink {
        /**
         * @param pcm interleaved signed 16-bit samples, native order
         * @param count number of valid shorts in [pcm]
         * @param channels 1 or 2
         * @param sampleRate Hz
         * @param firstFrameTimeMs elapsedRealtime (ms) of the first frame in the block
         */
        fun onBlock(pcm: ShortArray, count: Int, channels: Int, sampleRate: Int, firstFrameTimeMs: Long)
    }

    /** Short human description for the UI/log, e.g. "MIC 48 kHz" or "ECHO 48 kHz". */
    val label: String

    /** Begins delivering blocks to [sink]. Throws on fatal setup failure. */
    fun start(sink: Sink)

    /** Stops delivery and releases resources. Safe to call more than once. */
    fun stop()
}
