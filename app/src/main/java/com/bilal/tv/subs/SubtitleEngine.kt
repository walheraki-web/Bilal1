package com.bilal.tv.subs

import android.content.Context
import com.bilal.tv.capture.TvAudioSource
import com.bilal.tv.util.BLog
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Turns the movie's spoken audio into Arabic subtitles:
 *   TV audio -> resample to 16 kHz mono -> Vosk recognition (source language)
 *            -> ML Kit translation to Arabic -> [onSubtitle].
 *
 * Partial results update the line live; final results translate and commit it.
 * Everything runs on-device; the speech model and the translation model are
 * fetched once and cached.
 */
class SubtitleEngine(
    private val context: Context,
    private val sourceLang: String,
    /** (arabicText, isFinal) on the main/UI thread's caller responsibility. */
    private val onSubtitle: (String, Boolean) -> Unit,
    private val onStatus: (String) -> Unit,
) : TvAudioSource.Sink {

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "bilal-subs").apply { isDaemon = true } }
    @Volatile private var model: Model? = null
    @Volatile private var recognizer: Recognizer? = null
    @Volatile private var translator: Translator? = null
    @Volatile private var running = false

    // 48 kHz -> 16 kHz decimation state
    private var acc = 0.0
    private var accN = 0
    private val out16 = ShortArray(3200)
    private var out16n = 0
    private var ratioRem = 0.0

    private val lastPartial = AtomicReference("")

    fun start() {
        running = true
        worker.execute {
            try {
                onStatus("يجهّز التعرف على الكلام ($sourceLang)…")
                val path = SpeechModels.ensure(context, sourceLang) { f ->
                    onStatus("تنزيل نموذج الكلام ${(f * 100).toInt()}%")
                }
                model = Model(path.absolutePath)
                recognizer = Recognizer(model, 16000f)
                setupTranslator()
                onStatus("الترجمة الحية تعمل")
                BLog.i("subtitles started ($sourceLang → ar)")
            } catch (e: Exception) {
                running = false
                BLog.e("subtitle start", e)
                onStatus("تعذّر تشغيل الترجمة: ${e.message}")
            }
        }
    }

    private fun setupTranslator() {
        val src = TranslateLanguage.fromLanguageTag(sourceLang) ?: TranslateLanguage.ENGLISH
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(src)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
        val t = Translation.getClient(options)
        translator = t
        val conditions = DownloadConditions.Builder().build()
        // Block this worker until the translation model is present.
        val latch = java.util.concurrent.CountDownLatch(1)
        t.downloadModelIfNeeded(conditions)
            .addOnSuccessListener { latch.countDown() }
            .addOnFailureListener { e -> BLog.e("translate model", e); latch.countDown() }
        latch.await()
    }

    override fun onBlock(pcm: ShortArray, count: Int, channels: Int, sampleRate: Int, firstFrameTimeMs: Long) {
        if (!running) return
        // Downmix + resample to 16 kHz mono on the capture thread, then hand the
        // accumulated 16 kHz buffer to the worker for recognition.
        val step = sampleRate / 16000.0
        var i = 0
        while (i < count) {
            var s = 0
            for (c in 0 until channels) s += pcm[i + c]
            acc += (s / channels).toDouble(); accN++
            i += channels
            ratioRem += 1.0
            if (ratioRem >= step) {
                ratioRem -= step
                if (accN > 0) {
                    out16[out16n++] = (acc / accN).toInt().coerceIn(-32768, 32767).toShort()
                    acc = 0.0; accN = 0
                    if (out16n == out16.size) {
                        val chunk = out16.copyOf(out16n); val n = out16n; out16n = 0
                        worker.execute { recognise(chunk, n) }
                    }
                }
            }
        }
    }

    private fun recognise(buf: ShortArray, n: Int) {
        val rec = recognizer ?: return
        try {
            if (rec.acceptWaveForm(buf, n)) {
                val text = JSONObject(rec.result).optString("text").trim()
                if (text.isNotEmpty()) translate(text, true)
                lastPartial.set("")
            } else {
                val partial = JSONObject(rec.partialResult).optString("partial").trim()
                if (partial.isNotEmpty() && partial != lastPartial.getAndSet(partial)) {
                    translate(partial, false)
                }
            }
        } catch (e: Exception) {
            BLog.e("recognise", e)
        }
    }

    private fun translate(text: String, isFinal: Boolean) {
        val t = translator ?: run { onSubtitle(text, isFinal); return }
        t.translate(text)
            .addOnSuccessListener { onSubtitle(it, isFinal) }
            .addOnFailureListener { onSubtitle(text, isFinal) }
    }

    fun stop() {
        running = false
        worker.execute {
            runCatching { recognizer?.close() }
            runCatching { model?.close() }
            runCatching { translator?.close() }
            recognizer = null; model = null; translator = null
        }
    }
}
