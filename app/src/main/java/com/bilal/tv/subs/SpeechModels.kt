package com.bilal.tv.subs

import android.content.Context
import com.bilal.tv.util.BLog
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads and unpacks a Vosk speech model on first use. Models are hosted by
 * the Vosk project; the small English model is ~40 MB. The unpacked model lives
 * in filesDir/vosk/<name> and is reused afterwards.
 */
object SpeechModels {

    /** Source-language code -> (folder name, download URL). */
    private val MODELS = mapOf(
        "en" to ("vosk-model-small-en-us-0.15"
            to "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"),
        "fr" to ("vosk-model-small-fr-0.22"
            to "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"),
        "es" to ("vosk-model-small-es-0.42"
            to "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"),
        "tr" to ("vosk-model-small-tr-0.3"
            to "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip"),
    )

    fun isSupported(lang: String) = MODELS.containsKey(lang)

    fun localPath(context: Context, lang: String): File? {
        val name = MODELS[lang]?.first ?: return null
        val dir = File(File(context.filesDir, "vosk"), name)
        return if (File(dir, "am").exists() || File(dir, "conf").exists()) dir else null
    }

    /**
     * Ensures the model for [lang] is present, downloading if needed.
     * @param progress fraction 0..1 (download only)
     */
    fun ensure(context: Context, lang: String, progress: (Float) -> Unit): File {
        localPath(context, lang)?.let { return it }
        val (name, url) = MODELS[lang] ?: error("no speech model for $lang")
        val root = File(context.filesDir, "vosk").apply { mkdirs() }
        val target = File(root, name)
        BLog.i("downloading speech model $name")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000
        }
        conn.inputStream.use { input ->
            val total = conn.contentLengthLong.coerceAtLeast(1)
            var read = 0L
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val out = File(root, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                fos.write(buf, 0, n)
                                read += n
                                progress((read.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        if (!File(target, "conf").exists() && !File(target, "am").exists()) {
            error("speech model unpacked but looks incomplete")
        }
        BLog.i("speech model ready: $name")
        return target
    }
}
