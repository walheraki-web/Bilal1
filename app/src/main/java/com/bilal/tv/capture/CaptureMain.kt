package com.bilal.tv.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.Process
import android.os.SystemClock
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.lang.reflect.Method

/**
 * Privileged side of internal capture. Started by app_process as the shell user:
 *
 *   CLASSPATH=<apk> app_process /system/bin com.bilal.tv.capture.CaptureMain
 *
 * It records the receiver's internal audio output and streams it to the app over
 * the [CaptureProtocol.SOCKET] LocalSocket. It never touches the network and
 * exits when the socket closes.
 *
 * Internal capture uses a system capture preset reached by reflection, since the
 * SDK does not expose it; running as shell (uid 2000) is what makes it permitted.
 */
object CaptureMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val socket = android.net.LocalSocket()
        try {
            socket.connect(android.net.LocalSocketAddress(CaptureProtocol.SOCKET))
            val out = DataOutputStream(BufferedOutputStream(socket.outputStream))
            val record = try {
                buildInternalRecord()
            } catch (e: Throwable) {
                out.writeInt(CaptureProtocol.MAGIC_ERR)
                out.writeUTF("internal capture unavailable: ${e.message}")
                out.flush()
                return
            }
            val rate = 48000
            val channels = 2
            out.writeInt(CaptureProtocol.MAGIC_PCM)
            out.writeInt(2)
            out.writeInt(rate)
            out.writeInt(channels)
            out.writeInt(16)
            out.flush()

            record.startRecording()
            val ts = AudioTimestamp()
            val bytes = ByteArray(CaptureProtocol.BLOCK_BYTES)
            var framePos = 0L
            try {
                while (true) {
                    var off = 0
                    while (off < bytes.size) {
                        val r = record.read(bytes, off, bytes.size - off)
                        if (r <= 0) {
                            out.writeLong(-1)
                            out.writeUTF("capture read $r")
                            out.flush()
                            return
                        }
                        off += r
                    }
                    val frames = bytes.size / (channels * 2)
                    val firstTimeMs =
                        if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                            (framePos - ts.framePosition) * 1000 / rate + ts.nanoTime / 1_000_000
                        } else {
                            SystemClock.elapsedRealtime()
                        }
                    var rms = 0.0
                    var i = 0
                    while (i < bytes.size) {
                        val s = ((bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
                        rms += (s * s).toDouble()
                        i += 2
                    }
                    out.writeLong(firstTimeMs)
                    out.writeInt(bytes.size)
                    out.writeFloat(Math.sqrt(rms / (bytes.size / 2)).toFloat() / 32768f)
                    out.write(bytes, 0, bytes.size)
                    out.flush()
                    framePos += frames
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        } catch (_: Exception) {
            // socket closed by the app: normal shutdown
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Builds an AudioRecord on the internal output mix. Uses reflection for the
     * hidden setContext/setInternalCapturePreset APIs; the concrete preset value
     * varies by platform, so the helper logs and reports failure rather than
     * guessing wrong.
     */
    @SuppressLint("MissingPermission")
    private fun buildInternalRecord(): AudioRecord {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val format = AudioFormat.Builder()
            .setSampleRate(48000)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val builder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(48000 * 2 * 2) // ~1 s

        // Internal/loopback capture preset (REMOTE_SUBMIX family). Exposed only
        // to privileged callers, hence reflection + shell uid.
        val attrClass = Class.forName("android.media.AudioAttributes\$Builder")
        val attrBuilder = attrClass.getConstructor().newInstance()
        val setPreset: Method = attrClass.getDeclaredMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
        setPreset.isAccessible = true
        setPreset.invoke(attrBuilder, 8) // MediaRecorder.AudioSource.REMOTE_SUBMIX
        val attrs = attrClass.getMethod("build").invoke(attrBuilder)
        val setAttrs = AudioRecord.Builder::class.java
            .getDeclaredMethod("setAudioAttributes", Class.forName("android.media.AudioAttributes"))
        setAttrs.isAccessible = true
        setAttrs.invoke(builder, attrs)

        val record = builder.build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord not initialised")
        }
        return record
    }
}
