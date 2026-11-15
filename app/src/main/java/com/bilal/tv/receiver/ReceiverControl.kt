package com.bilal.tv.receiver

import android.content.Context
import android.media.AudioManager
import com.bilal.tv.util.BLog
import java.io.File
import java.lang.reflect.Method
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/**
 * Talks to the receiver box: sets its picture (video) delay and mutes the TV
 * channel's own sound. Both are best-effort and platform specific; each method
 * reports whether it did anything.
 */
class ReceiverControl(context: Context) {

    private val app = context.applicationContext
    @Volatile var tvMuted = false
        private set

    /**
     * Sets the receiver's picture delay to [ms] (0..60000, multiple of 500).
     * The common DVB stack listens on a local UDP port for this command; if your
     * box differs, this is the single place to adapt.
     */
    fun setVideoDelay(ms: Int) {
        require(ms in 0..60_000 && ms % 500 == 0) { "video delay out of range" }
        val payload = "Play\n0118\n$ms".toByteArray(StandardCharsets.US_ASCII)
        DatagramSocket().use { s ->
            s.send(DatagramPacket(payload, payload.size, InetAddress.getByName("127.0.0.1"), 51021))
        }
        BLog.i("video delay command $ms ms")
    }

    /** Mutes (or unmutes) the live channel's audio so only the station is heard. */
    fun muteTv(mute: Boolean): Boolean {
        var done = false
        runCatching {
            (app.getSystemService(AudioManager::class.java))
                .setParameters("hal_param_tv_mute=" + if (mute) 1 else 0)
            done = true
        }
        runCatching { if (droidLogicMute(mute)) done = true }
        if (done) tvMuted = mute
        return done
    }

    // --- optional DroidLogic path, by reflection if the framework jar is present ---
    private var dlChecked = false
    private var dlInstance: Any? = null
    private var dlMethod: Method? = null

    private fun droidLogicMute(mute: Boolean): Boolean {
        if (!dlChecked) {
            dlChecked = true
            val jars = listOf(
                "/vendor/framework/droidlogic-tv.jar",
                "/system/framework/droidlogic-tv.jar",
                "/system/framework/droidlogic.jar",
            )
            var cls: Class<*>? = runCatching { Class.forName("com.droidlogic.app.tv.TvControlManager") }.getOrNull()
            if (cls == null) {
                for (p in jars) if (File(p).exists()) {
                    cls = runCatching {
                        dalvik.system.PathClassLoader(p, ClassLoader.getSystemClassLoader())
                            .loadClass("com.droidlogic.app.tv.TvControlManager")
                    }.getOrNull()
                    if (cls != null) break
                }
            }
            if (cls != null) runCatching {
                dlInstance = cls.getMethod("getInstance").invoke(null)
                dlMethod = cls.methods.firstOrNull {
                    it.name.equals("SetAmAudioPreMute", true) && it.parameterTypes.size == 1
                }
            }
        }
        val m = dlMethod ?: return false
        val inst = dlInstance ?: return false
        return runCatching { m.invoke(inst, if (mute) 1 else 0); true }.getOrDefault(false)
    }
}
