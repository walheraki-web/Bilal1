package com.bilal.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import com.bilal.tv.audio.StationPlayer
import com.bilal.tv.capture.InternalCaptureSource
import com.bilal.tv.capture.MicTvAudioSource
import com.bilal.tv.capture.ShizukuCapture
import com.bilal.tv.capture.TvAudioSource
import com.bilal.tv.receiver.ReceiverControl
import com.bilal.tv.subs.SrtController
import com.bilal.tv.subs.SubtitleOverlay
import com.bilal.tv.sync.FeatureTrack
import com.bilal.tv.sync.SyncCoordinator
import com.bilal.tv.sync.SyncPolicy
import com.bilal.tv.util.BLog
import com.bilal.tv.util.Prefs
import com.bilal.tv.util.Station

/**
 * The always-on brain. Plays the station, keeps the A/V sync, recovers from
 * network drops (and re-syncs automatically), and drives live subtitles.
 *
 * Bound by [ui.MainActivity]; also a media foreground service so playback
 * survives the UI being closed.
 */
@UnstableApi
class BilalService : Service() {

    inner class LocalBinder : android.os.Binder() {
        val service get() = this@BilalService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())

    lateinit var prefs: Prefs; private set
    private lateinit var player: StationPlayer
    private lateinit var receiver: ReceiverControl
    private val tvTrack = FeatureTrack()
    private val stationTrack = FeatureTrack()

    /** Pluggable TV-audio source. Defaults to the microphone; switches to
     *  privileged internal capture once the user enables Shizuku. */
    var tvAudioSource: TvAudioSource = MicTvAudioSource()

    /**
     * Switches the TV-audio reference to privileged internal capture via Shizuku,
     * so sync works on a receiver box with no microphone. Falls back to the mic
     * if Shizuku is not available. Re-starts capture if a station is playing.
     */
    fun enableReceiverCapture() {
        if (!ShizukuCapture.isAvailable()) {
            status("Shizuku غير مُفعّل — فعّله مرة واحدة عبر ADB ثم أعد المحاولة")
            return
        }
        ShizukuCapture.ensurePermission(
            onReady = {
                tvAudioSource = InternalCaptureSource(this) {
                    ShizukuCapture.bindAndStart(this, onStatus = { status(it) }, onError = { status(it) })
                }
                status("تم تفعيل التقاط صوت الريسيفر — سيبدأ عند تشغيل محطة")
                if (playing) startTvCapture()
            },
            onError = { status(it) },
        )
    }

    val receiverCaptureActive get() = tvAudioSource is InternalCaptureSource

    private var subs: SrtController? = null
    private var overlay: SubtitleOverlay? = null

    @Volatile var currentStation: Station? = null; private set
    @Volatile var playing = false; private set
    @Volatile var syncing = false; private set
    @Volatile var lastResult = "لم تُجرَ مزامنة بعد"; private set

    /** UI listener for status/result/sub lines. */
    var listener: Listener? = null
    interface Listener {
        fun onStatus(text: String) {}
        fun onResult(text: String) {}
    }

    private var syncJob: SyncCoordinator? = null

    override fun onCreate() {
        super.onCreate()
        BLog.init(this)
        prefs = Prefs(this)
        receiver = ReceiverControl(this)
        // Build the player defensively: if media3 fails to initialise on this box,
        // log it and keep the service (and UI) alive instead of crashing the app.
        try {
            player = StationPlayer(
                context = this,
                onTap = { buf, ch, rate, t -> pushStationTap(buf, ch, rate, t) },
                onRecovered = { onStreamRecovered() },
                onStatus = { status(it) },
            )
            playerReady = true
            BLog.i("player ready")
        } catch (t: Throwable) {
            BLog.e("player init failed", t)
            lastResult = "تعذّر تهيئة المشغّل: ${t.message}"
        }
        createChannel()
        runCatching { startForeground(1, buildNotification(lastResult)) }
            .onFailure { BLog.e("startForeground", it) }
    }

    @Volatile private var playerReady = false

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    // --- playback ---

    fun play(station: Station) {
        if (!playerReady) { status("المشغّل غير جاهز — راجع السجل"); return }
        currentStation = station
        prefs.lastStationUrl = station.url
        playing = true
        player.setVolume(prefs.stationVolume(station.url))
        player.audioDelayMs = prefs.stationDelay(station.url)
        player.play(station.url)
        startTvCapture()
        status("يشغّل ${station.name}")
    }

    fun stopPlayback() {
        playing = false
        syncJob?.cancel(); syncJob = null; syncing = false
        if (playerReady) player.stop()
        stopTvCapture()
        runCatching { receiver.muteTv(false) }
        status("توقف")
    }

    private fun startTvCapture() {
        stopTvCapture()
        runCatching {
            tvAudioSource.start { pcm, count, ch, rate, t ->
                tvTrack.push(pcm, count, ch, rate, t.toDouble())
            }
            status("مصدر صوت القناة: ${tvAudioSource.label}")
        }.onFailure {
            BLog.e("tv capture", it)
            status("تعذّر التقاط صوت القناة: ${it.message}")
        }
    }

    private fun stopTvCapture() = runCatching { tvAudioSource.stop() }

    private fun pushStationTap(buf: java.nio.ByteBuffer, channels: Int, rate: Int, timeMs: Long) {
        val n = buf.remaining() / 2
        val shorts = ShortArray(n)
        buf.asShortBuffer().get(shorts)
        stationTrack.push(shorts, n, channels, rate, timeMs.toDouble())
    }

    // --- sync ---

    fun sync() {
        val station = currentStation ?: run { status("شغّل محطة أولًا"); return }
        if (!playing) { status("شغّل محطة أولًا"); return }
        syncJob?.cancel()
        receiver.muteTv(false) // hear the channel while measuring
        syncing = true
        val job = SyncCoordinator(
            prefs = prefs,
            player = player,
            receiver = receiver,
            stationUrl = station.url,
            tv = tvTrack,
            station = stationTrack,
            onStatus = { status(it) },
            onResult = { r -> main.post { syncing = false; result(r) } },
        )
        syncJob = job
        job.start()
    }

    fun cancelSync() {
        syncJob?.cancel(); syncJob = null; syncing = false
        if (playerReady) player.setTapping(false)
        status("أُلغيت المزامنة")
    }

    // --- manual delay / mute controls (the ZHE-style panel) ---

    val videoDelayMs get() = prefs.videoDelayMs
    val audioDelayMs get() = if (playerReady) player.audioDelayMs else 0
    val tvMuted get() = receiver.tvMuted
    var autoResync: Boolean
        get() = prefs.resyncAfterDrop
        set(v) { prefs.resyncAfterDrop = v; status(if (v) "المزامنة التلقائية مفعّلة" else "المزامنة التلقائية مطفأة") }

    /** Step the receiver picture delay by [deltaMs] (snapped to 500 ms, 0..60 s). */
    fun nudgeVideoDelay(deltaMs: Int) {
        if (!prefs.controlVideoDelay) { status("التحكم بتأخير الصورة مُعطّل في الخيارات"); return }
        val snapped = ((prefs.videoDelayMs + deltaMs) / 500 * 500).coerceIn(0, SyncPolicy.VIDEO_MAX_MS)
        runCatching { receiver.setVideoDelay(snapped) }
            .onFailure { status("تعذّر ضبط تأخير الصورة: ${it.message}"); return }
        prefs.videoDelayMs = snapped
        status("تأخير الصورة: ${snapped / 1000.0} ث")
    }

    /** Step the station audio delay by [deltaMs] (0..120 s). */
    fun nudgeAudioDelay(deltaMs: Int) {
        if (!playerReady) { status("المشغّل غير جاهز"); return }
        val next = (player.audioDelayMs + deltaMs).coerceIn(0, SyncPolicy.AUDIO_MAX_MS)
        player.audioDelayMs = next
        currentStation?.let { prefs.setStationDelay(it.url, next) }
        status("تأخير الصوت: ${next / 1000.0} ث")
    }

    /** Toggle whether the live channel's own sound is muted. */
    fun toggleMuteTv(): Boolean {
        val mute = !receiver.tvMuted
        runCatching { receiver.muteTv(mute) }
            .onFailure { status("تعذّر كتم القناة: ${it.message}") }
        status(if (receiver.tvMuted) "المسموع الآن: الصوتية (القناة مكتومة)" else "المسموع الآن: صوت القناة")
        return receiver.tvMuted
    }

    private fun onStreamRecovered() {
        if (playing && prefs.resyncAfterDrop && currentStation != null) {
            result("عاد البث • إعادة مزامنة تلقائية")
            sync()
        }
    }

    // --- subtitles ---

    private fun ensureSubtitles(): SrtController {
        subs?.let { return it }
        val ov = SubtitleOverlay(this)
        overlay = ov
        return SrtController(this, ov, onStatus = { status(it) }).also { subs = it }
    }

    /** Load an SRT file (local path or http[s] URL). */
    fun loadSubtitles(source: String) {
        ensureSubtitles().load(source) { }
    }

    /** Start subtitles, treating now as movie time [atMs]. */
    fun startSubtitles(atMs: Long = 0) = ensureSubtitles().start(atMs)

    fun stopSubtitles() = subs?.stop()

    /** Nudge timing by [deltaMs] (negative = show earlier). */
    fun nudgeSubtitles(deltaMs: Long) = ensureSubtitles().nudge(deltaMs)

    val subtitlesLoaded get() = subs?.loaded == true
    val subtitlesRunning get() = subs?.isRunning == true

    // --- helpers ---

    private fun status(text: String) {
        BLog.i("status: $text")
        main.post { listener?.onStatus(text) }
    }

    private fun result(text: String) {
        lastResult = text
        updateNotification(text)
        main.post { listener?.onResult(text) }
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel("bilal", "Bilal", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, "bilal")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Bilal")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))

    override fun onDestroy() {
        stopPlayback()
        subs?.release(); subs = null
        if (playerReady) runCatching { player.release() }
        super.onDestroy()
    }
}
