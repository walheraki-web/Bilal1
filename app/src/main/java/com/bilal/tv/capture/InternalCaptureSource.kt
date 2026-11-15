package com.bilal.tv.capture

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.SystemClock
import com.bilal.tv.util.BLog
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Internal (loop-back) capture of the receiver's own audio output — the clean
 * path that needs no microphone. The actual recording is done by [CaptureMain]
 * running as the shell user (uid 2000) via app_process, because internal capture
 * needs a system preset the app itself may not hold; it streams PCM back here
 * over a [CaptureProtocol.SOCKET] LocalSocket.
 *
 * Starting that helper requires one shell command on the receiver, which is what
 * [launcher] performs (typically over the device's own adbd). Everything after
 * the helper is running lives in this file.
 */
class InternalCaptureSource(
    private val context: Context,
    private val launcher: HelperLauncher,
) : TvAudioSource {

    /** Launches the capture helper as the shell user. */
    fun interface HelperLauncher {
        /**
         * Run, as uid 2000:
         *   CLASSPATH=<apk> app_process /system/bin com.bilal.tv.capture.CaptureMain
         * The process must outlive this call (it streams until the socket closes).
         */
        fun launch(classpath: String)
    }

    override val label: String get() = labelText
    @Volatile private var labelText = "ECHO"

    @Volatile private var running = false
    private var server: LocalServerSocket? = null
    private var client: LocalSocket? = null
    private var worker: Thread? = null

    override fun start(sink: TvAudioSource.Sink) {
        running = true
        val srv = LocalServerSocket(CaptureProtocol.SOCKET)
        server = srv
        worker = thread(name = "bilal-echo", isDaemon = true) {
            try {
                launcher.launch(context.applicationInfo.sourceDir)
                val sock = srv.accept()
                client = sock
                if (sock.peerCredentials.uid != CaptureProtocol.SHELL_UID) {
                    throw IOException("capture peer is not the shell user")
                }
                val input = DataInputStream(BufferedInputStream(sock.inputStream))
                when (input.readInt()) {
                    CaptureProtocol.MAGIC_ERR -> throw IOException(input.readUTF())
                    CaptureProtocol.MAGIC_PCM -> {}
                    else -> throw IOException("bad capture header")
                }
                val version = input.readInt()
                val rate = input.readInt()
                val channels = input.readInt()
                val bits = input.readInt()
                if (bits != 16 || channels !in 1..2) throw IOException("unsupported capture format")
                labelText = "ECHO ${rate / 1000} kHz"
                BLog.i("internal capture: $labelText v$version")
                val shorts = ShortArray(CaptureProtocol.BLOCK_BYTES / 2)
                val bytes = ByteArray(CaptureProtocol.BLOCK_BYTES)
                while (running) {
                    val t = input.readLong()
                    if (t < 0) throw IOException(input.readUTF())
                    val n = input.readInt()
                    if (n > bytes.size) throw IOException("oversized block $n")
                    if (version >= 2) input.readFloat() // reserved: per-block RMS
                    input.readFully(bytes, 0, n)
                    if (kotlin.math.abs(SystemClock.elapsedRealtime() - t) > 10_000) {
                        throw IOException("capture timestamp out of range")
                    }
                    var j = 0
                    var i = 0
                    while (i < n) {
                        shorts[j++] = ((bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)).toShort()
                        i += 2
                    }
                    sink.onBlock(shorts, j, channels, rate, t)
                }
            } catch (e: Exception) {
                if (running) BLog.e("internal capture", e)
            }
        }
    }

    override fun stop() {
        running = false
        client?.runCatching { close() }
        server?.runCatching { close() }
        worker?.join(1000)
        worker = null
        client = null
        server = null
    }
}
