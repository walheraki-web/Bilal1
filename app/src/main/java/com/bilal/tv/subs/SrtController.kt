package com.bilal.tv.subs

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.bilal.tv.util.BLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Drives the bottom subtitle bar from a loaded SRT file.
 *
 * Bilal does not play the video (the receiver does), so there is no media clock
 * to lock onto. Instead the user starts the subtitles when the film starts and
 * nudges the offset until the lines line up; the chosen offset is remembered per
 * file. The clock is:  moviePos = (now - startedAt) + baseMs + offsetMs.
 */
class SrtController(
    private val context: Context,
    private val overlay: SubtitleOverlay,
    private val onStatus: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var srt: Srt? = null

    private var running = false
    private var startedAt = 0L
    private var baseMs = 0L
    var offsetMs: Long = 0
        private set

    private val tick = object : Runnable {
        override fun run() {
            val s = srt ?: return
            val pos = SystemClock.elapsedRealtime() - startedAt + baseMs + offsetMs
            val cue = s.at(pos)
            if (cue != null) overlay.setPersistent(cue.text) else overlay.clear()
            if (running) main.postDelayed(this, 150)
        }
    }

    val loaded get() = srt != null
    val isRunning get() = running

    /** Load from a local path or an http(s) URL. */
    fun load(source: String, onDone: (Boolean) -> Unit) {
        onStatus("تحميل ملف الترجمة…")
        thread(isDaemon = true) {
            val ok = runCatching {
                val raw = if (source.startsWith("http", true)) download(source)
                else File(source).readText(Charsets.UTF_8)
                val parsed = Srt.parse(raw)
                if (parsed.cues.isEmpty()) error("لا توجد أسطر ترجمة صالحة في الملف")
                srt = parsed
                BLog.i("subtitles loaded: ${parsed.cues.size} cues from $source")
            }.isSuccess
            main.post {
                if (ok) onStatus("جاهز: ${srt?.cues?.size ?: 0} سطر ترجمة. اضغط ابدأ عند بداية الفيلم.")
                else onStatus("تعذّر تحميل ملف الترجمة")
                onDone(ok)
            }
        }
    }

    private fun download(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000; instanceFollowRedirects = true
        }
        conn.inputStream.use { return it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Start showing subtitles, treating "now" as movie position [atMs] (default 0). */
    fun start(atMs: Long = 0) {
        if (srt == null) { onStatus("حمّل ملف ترجمة أولًا"); return }
        startedAt = SystemClock.elapsedRealtime()
        baseMs = atMs
        running = true
        main.removeCallbacks(tick)
        main.post(tick)
        overlay.show()
        onStatus("الترجمة تعمل")
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
        overlay.clear()
        onStatus("أُوقفت الترجمة")
    }

    /** Nudge timing. Positive = subtitles appear earlier (move clock forward). */
    fun nudge(deltaMs: Long) {
        offsetMs += deltaMs
        onStatus("إزاحة الترجمة: ${offsetMs / 1000.0} ث")
    }

    fun release() { stop(); overlay.hide() }
}
