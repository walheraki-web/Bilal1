package com.bilal.tv.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.bilal.tv.util.BLog
import java.nio.ByteBuffer

/**
 * Plays the internet station and keeps it alive across network drops.
 *
 * Recovery is the heart of the fix:
 *   - unlimited reconnect with capped back-off (never gives up after N tries),
 *   - an immediate retry the moment the network returns (ConnectivityManager),
 *   - explicit handling of "behind the live window" (HLS after a long gap),
 *   - and a [onRecovered] callback once the stream is healthy again, which the
 *     service uses to re-measure the A/V sync automatically.
 *
 * All methods must be called on the main thread (ExoPlayer's thread).
 */
@UnstableApi
class StationPlayer(
    private val context: Context,
    private val onTap: (ByteBuffer, Int, Int, Long) -> Unit,
    /** Invoked after the stream recovers from an interruption and settles. */
    private val onRecovered: () -> Unit,
    /** Invoked with a short status line for the UI/log. */
    private val onStatus: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val delay = DelayAudioProcessor()
    private lateinit var tap: TappingAudioSink

    val player: ExoPlayer

    private var url: String? = null
    @Volatile private var wantPlaying = false
    private var reconnectAttempt = 0
    private var interrupted = false
    private var recoverAtMs = 0L
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** Station audio delay in milliseconds (the fine part of sync). */
    var audioDelayMs: Int
        get() = delay.delayMs
        set(v) { delay.delayMs = v.coerceIn(0, 120_000) }

    init {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                val base = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(delay))
                    .build()
                tap = TappingAudioSink(base, onTap)
                return tap
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .setAudioAttributes(AudioAttributes.DEFAULT, false)
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onError(error)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && interrupted) {
                    // Give the buffer a moment to stabilise before re-measuring.
                    recoverAtMs = SystemClock.elapsedRealtime() + 6000
                    main.postDelayed(::checkRecovered, 6000)
                }
                if (state == Player.STATE_READY && reconnectAttempt > 0 && !interrupted) {
                    reconnectAttempt = 0
                }
            }
        })
        registerNetworkCallback()
    }

    /** Enables tapping of station PCM (only needed while measuring sync). */
    fun setTapping(on: Boolean) { if (::tap.isInitialized) tap.tapping = on }

    fun play(url: String) {
        this.url = url
        wantPlaying = true
        reconnectAttempt = 0
        interrupted = false
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    fun stop() {
        wantPlaying = false
        interrupted = false
        main.removeCallbacksAndMessages(null)
        player.stop()
        player.clearMediaItems()
    }

    fun setVolume(v: Float) { player.volume = v.coerceIn(0f, 1f) }

    fun release() {
        unregisterNetworkCallback()
        main.removeCallbacksAndMessages(null)
        player.release()
    }

    private fun onError(error: PlaybackException) {
        if (!wantPlaying) return
        interrupted = true
        val u = url ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            BLog.i("stream fell behind the live window; jumping to live")
            onStatus("انقطع البث لحظة؛ بالعودة للبث المباشر")
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        reconnectAttempt++
        val backoff = minOf(30_000L, 1_000L * reconnectAttempt)
        BLog.i("stream error ${error.errorCodeName}; reconnect #$reconnectAttempt in ${backoff}ms")
        onStatus("انقطع البث • إعادة الاتصال رقم $reconnectAttempt")
        main.postDelayed({
            if (wantPlaying) {
                player.setMediaItem(MediaItem.fromUri(u))
                player.prepare()
                player.playWhenReady = true
            }
        }, backoff)
    }

    private fun checkRecovered() {
        if (!interrupted || !wantPlaying) return
        if (player.playbackState != Player.STATE_READY || !player.playWhenReady) {
            main.postDelayed(::checkRecovered, 2000)
            return
        }
        if (SystemClock.elapsedRealtime() < recoverAtMs) {
            main.postDelayed(::checkRecovered, recoverAtMs - SystemClock.elapsedRealtime())
            return
        }
        interrupted = false
        reconnectAttempt = 0
        BLog.i("stream recovered; requesting re-sync")
        onRecovered()
    }

    private fun registerNetworkCallback() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    if (wantPlaying && interrupted && player.playbackState == Player.STATE_IDLE) {
                        BLog.i("network back; retrying immediately")
                        url?.let {
                            player.setMediaItem(MediaItem.fromUri(it))
                            player.prepare()
                            player.playWhenReady = true
                        }
                    }
                }
            }
        }
        netCallback = cb
        runCatching {
            cm?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb,
            )
        }
    }

    private fun unregisterNetworkCallback() {
        netCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        netCallback = null
    }
}
