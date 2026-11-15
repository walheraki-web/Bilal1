package com.bilal.tv.sync

import android.os.SystemClock
import com.bilal.tv.audio.StationPlayer
import com.bilal.tv.receiver.ReceiverControl
import com.bilal.tv.util.BLog
import com.bilal.tv.util.Prefs
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Runs one sync measurement and applies the result.
 *
 * Flow:
 *  1. Remove the station audio delay so the station timeline is "raw".
 *  2. Tap both the TV reference and the station into feature timelines.
 *  3. Correlate over a growing window until two consecutive windows agree with
 *     high confidence (robust against a single noisy match).
 *  4. Turn the lag into a plan: step the receiver's picture delay (in <=8 s
 *     jumps it tolerates) and set the fine station audio delay.
 *  5. Remember the lag per station and, if asked, mute the live channel.
 *
 * A fresh coordinator is used per attempt; [cancel] stops it.
 */
class SyncCoordinator(
    private val prefs: Prefs,
    private val player: StationPlayer,
    private val receiver: ReceiverControl,
    private val stationUrl: String,
    private val tv: FeatureTrack,
    private val station: FeatureTrack,
    private val onStatus: (String) -> Unit,
    private val onResult: (String) -> Unit,
) {
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "bilal-sync").apply { isDaemon = true } }
    private val cancelled = AtomicBoolean(false)

    fun start() { exec.execute { runCatching { run() }.onFailure { fail(it) } } }

    fun cancel() { cancelled.set(true) }

    private fun fail(t: Throwable) {
        if (cancelled.get()) return
        BLog.e("sync", t)
        onResult("لم تنجح المزامنة: ${t.message}")
    }

    private fun check() { if (cancelled.get()) throw InterruptedException("cancelled") }

    private fun run() {
        onStatus("يقيس المزامنة فوق القناة الحية")
        // 1. raw station timeline
        player.audioDelayMs = 0
        tv.reset()
        station.reset()
        player.setTapping(true)

        val f = FeatureTrack.FRAME_MS
        val start = SystemClock.elapsedRealtime()

        // 2. wait until both sides are producing features
        waitForContent(start)

        // 3. correlate over growing windows
        val memory = prefs.lastLag(stationUrl)
        val searchMs = maxOf(20_000L, memory?.let { abs(it) + 4000L } ?: 0L)
        BLog.i("sync search +-$searchMs ms" + (memory?.let { " (memory $it)" } ?: ""))

        var previous: Correlator.Result? = null
        var accepted: Correlator.Result? = null
        val deadline = start + 45_000
        while (SystemClock.elapsedRealtime() < deadline) {
            check()
            Thread.sleep(400)
            val newestTv = tv.newest
            val newestSt = station.newest
            if (newestTv == Long.MIN_VALUE || newestSt == Long.MIN_VALUE) continue
            val to = minOf(newestTv, newestSt - searchMs / f)
            val from = to - (18_000L / f) // ~18 s window
            if (to - from < 300) continue
            val r = Correlator.correlate(tv, station, from, to, -searchMs, searchMs) ?: continue
            onStatus("يطابق الصوت… ${((SystemClock.elapsedRealtime() - start) / 1000)} ث")
            if (SyncPolicy.accept(r)) {
                val prev = previous
                if (prev != null && SyncPolicy.agree(prev, r)) {
                    accepted = r
                    break
                }
                previous = r
            } else {
                previous = null
            }
        }

        val result = accepted ?: run {
            onResult("تعذّر إيجاد تطابق موثوق بين صوت القناة والصوتية؛ أبقِ صوت القناة مسموعًا وأعد المحاولة")
            player.setTapping(false)
            return
        }
        BLog.i("sync result: $result")

        // 4. apply
        apply(result.lagMs)
    }

    private fun waitForContent(start: Long) {
        while (SystemClock.elapsedRealtime() - start < 25_000) {
            check()
            val okTv = tv.newest != Long.MIN_VALUE
            val okSt = station.newest != Long.MIN_VALUE
            if (okTv && okSt) return
            if (SystemClock.elapsedRealtime() - start > 2000) {
                onStatus(
                    "بانتظار الصوت • " +
                        (if (okTv) "القناة ✓" else "القناة…") + " " +
                        (if (okSt) "الصوتية ✓" else "الصوتية…"),
                )
            }
            Thread.sleep(200)
        }
        if (tv.newest == Long.MIN_VALUE) throw IllegalStateException("لم يصل صوت القناة. أبقِ القناة تعمل بصوت مسموع")
        throw IllegalStateException("لم يبدأ بث الصوتية. تأكد من الشبكة")
    }

    private fun apply(lagMs: Double) {
        val currentVideo = if (prefs.controlVideoDelay) prefs.videoDelayMs else 0
        val plan = SyncPolicy.plan(lagMs, currentVideo)

        if (plan.needsVideoChange && prefs.controlVideoDelay) {
            var cur = currentVideo
            while (cur != plan.videoDelayMs) {
                check()
                val next = SyncPolicy.nextVideoStep(cur, plan.videoDelayMs)
                onStatus("تأخير الصورة ${next / 1000.0} ث…")
                receiver.setVideoDelay(next)
                prefs.videoDelayMs = next
                cur = next
                // let the receiver restart its player between steps
                if (cur != plan.videoDelayMs) Thread.sleep(2500)
            }
        }

        player.audioDelayMs = plan.audioDelayMs
        prefs.setStationDelay(stationUrl, plan.audioDelayMs)
        prefs.setLastLag(stationUrl, lagMs.toInt())
        player.setTapping(false)

        if (prefs.muteTvAfterSync) {
            if (receiver.muteTv(true)) onStatus("صوت القناة مكتوم")
        }
        BLog.i("applied: video ${plan.videoDelayMs} ms, audio ${plan.audioDelayMs} ms (lag ${lagMs.toInt()})")
        onResult("تمت المزامنة ✓")
    }
}
