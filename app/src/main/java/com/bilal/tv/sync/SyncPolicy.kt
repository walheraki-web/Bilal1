package com.bilal.tv.sync

import kotlin.math.abs
import kotlin.math.ceil

/** Pure decision rules for the sync engine (JVM-testable). */
object SyncPolicy {
    const val MIN_Z = 7.0
    const val MIN_MARGIN = 2.5
    const val AGREE_MS = 60.0
    const val VIDEO_STEP_MS = 500
    const val VIDEO_MAX_MS = 60_000
    /** The receiver's player tends to fail on bigger single jumps. */
    const val VIDEO_MAX_JUMP_MS = 8_000
    const val AUDIO_MAX_MS = 120_000

    fun accept(r: Correlator.Result) = r.z >= MIN_Z && r.margin >= MIN_MARGIN

    fun agree(a: Correlator.Result, b: Correlator.Result) = abs(a.lagMs - b.lagMs) <= AGREE_MS

    /**
     * What to do with a measured lag.
     * @param lagMs station minus TV, measured with the station audio delay excluded
     * @param videoDelayMs the receiver picture delay in force while measuring
     */
    data class Plan(val videoDelayMs: Int, val audioDelayMs: Int, val needsVideoChange: Boolean)

    fun plan(lagMs: Double, videoDelayMs: Int): Plan {
        if (lagMs <= 0) {
            return Plan(videoDelayMs, (-lagMs).toInt().coerceAtMost(AUDIO_MAX_MS), false)
        }
        // The station is behind the TV: delay the picture (and TV sound) by at
        // least lag, in the receiver's 0.5 s steps, then delay the station by
        // the small remainder.
        val target = (ceil((videoDelayMs + lagMs) / VIDEO_STEP_MS) * VIDEO_STEP_MS).toInt()
        val v = target.coerceAtMost(VIDEO_MAX_MS)
        val remainder = (v - videoDelayMs - lagMs).toInt().coerceAtLeast(0)
        return Plan(v, remainder, v != videoDelayMs)
    }

    /** Next picture-delay command toward [target], respecting the receiver's jump limit. */
    fun nextVideoStep(current: Int, target: Int): Int =
        if (target - current > VIDEO_MAX_JUMP_MS) current + VIDEO_MAX_JUMP_MS else target
}
