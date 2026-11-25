package com.bilal.tv.capture

import kotlin.concurrent.thread

/**
 * Runs inside a Shizuku-spawned process as the shell user (uid 2000). Because it
 * runs as shell, it may use the internal/loop-back audio capture that a normal
 * app cannot. It reuses [CaptureMain], which records the box's audio output and
 * streams it to the app over the [CaptureProtocol.SOCKET] LocalSocket.
 *
 * Shizuku instantiates this class (no-arg constructor) in the privileged process
 * and binds the app to it via AIDL.
 */
class BilalCaptureService : IBilalCapture.Stub {

    @Suppress("unused")
    constructor()

    @Volatile private var worker: Thread? = null

    override fun startCapture() {
        if (worker != null) return
        worker = thread(name = "bilal-cap-shell", isDaemon = true) {
            runCatching { CaptureMain.main(emptyArray()) }
        }
    }

    /**
     * Discovery only: runs a fixed list of read-only commands as the shell user
     * to find how this DroidLogic box exposes the currently-tuned channel. The
     * commands take no input from the app; the combined output is returned so the
     * app (and we) can see which source names the live channel, then automate it.
     */
    override fun detectChannel(): String {
        val probes = listOf(
            "getprop | grep -iE 'channel|dtv|\\.tv\\.|program|service' ",
            "settings get system current_channel",
            "settings get secure current_channel",
            "settings get global current_channel",
            "dumpsys activity activities | grep -iE 'ResumedActivity|topResumedActivity' | head -5",
            "content query --uri content://android.media.tv/channel --projection _id:display_number:display_name 2>/dev/null | head -40",
            "content query --uri content://com.droidlogic.tvinput/channel 2>/dev/null | head -40",
            "dumpsys media_session 2>/dev/null | grep -iE 'package|metadata|title' | head -20",
        )
        val sb = StringBuilder()
        for (cmd in probes) {
            sb.append("\$ ").append(cmd).append('\n')
            val out = runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val text = p.inputStream.bufferedReader().readText() +
                    p.errorStream.bufferedReader().readText()
                p.waitFor()
                text
            }.getOrElse { "  <err: ${it.message}>\n" }
            sb.append(out.ifBlank { "  <empty>\n" }).append("\n")
        }
        return sb.toString()
    }

    override fun destroy() {
        worker?.interrupt()
        worker = null
    }
}
