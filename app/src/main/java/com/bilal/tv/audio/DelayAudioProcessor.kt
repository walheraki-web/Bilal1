package com.bilal.tv.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * A constant-delay line for the station audio. Holds a FIFO pre-filled with
 * [delayMs] of silence, so every output sample is the input from [delayMs]
 * earlier. The delay can be changed at runtime; the FIFO is grown with silence
 * or trimmed to match, without a flush, so sync adjustments are smooth.
 */
class DelayAudioProcessor : BaseAudioProcessor() {

    @Volatile var delayMs: Int = 0

    private var bytesPerMs = 0
    private var fifo = ByteArray(0)
    private var head = 0
    private var tail = 0
    private var fill = 0 // bytes currently buffered

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        bytesPerMs = inputAudioFormat.sampleRate * inputAudioFormat.channelCount * 2 / 1000
        return inputAudioFormat
    }

    override fun onFlush() {
        val target = delayMs * bytesPerMs
        fifo = ByteArray(maxOf(target * 2, bytesPerMs * 200).coerceAtLeast(4096))
        head = 0
        tail = 0
        fill = 0
        // Pre-fill with the configured delay of silence.
        ensureCapacity(target)
        for (i in 0 until target) { fifo[tail] = 0; tail = (tail + 1) % fifo.size }
        fill = target
    }

    override fun onReset() {
        fifo = ByteArray(0)
        head = 0; tail = 0; fill = 0
    }

    override fun queueInput(input: ByteBuffer) {
        val count = input.remaining()
        if (count == 0) return
        adjustDelay()
        // Push input into the FIFO.
        ensureCapacity(fill + count)
        for (i in 0 until count) { fifo[tail] = input.get(); tail = (tail + 1) % fifo.size; fill++ }
        // Emit `count` bytes (held behind by the current fill = delay).
        val emit = count
        val out = replaceOutputBuffer(emit)
        for (i in 0 until emit) { out.put(fifo[head]); head = (head + 1) % fifo.size; fill-- }
        out.flip()
    }

    private fun adjustDelay() {
        if (bytesPerMs == 0) return
        val target = delayMs * bytesPerMs
        val current = fill
        when {
            target > current -> { // grow: insert silence at the head
                val add = target - current
                ensureCapacity(fill + add)
                // shift nothing; prepend by moving head back and writing silence
                for (i in 0 until add) {
                    head = (head - 1 + fifo.size) % fifo.size
                    fifo[head] = 0
                    fill++
                }
            }
            target < current -> { // shrink: drop oldest buffered bytes
                val drop = current - target
                head = (head + drop) % fifo.size
                fill -= drop
            }
        }
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= fifo.size) return
        val grown = ByteArray(maxOf(needed, fifo.size * 2).coerceAtLeast(4096))
        // linearise existing content from head
        for (i in 0 until fill) grown[i] = fifo[(head + i) % fifo.size]
        fifo = grown
        head = 0
        tail = fill
    }

    override fun isActive(): Boolean = bytesPerMs > 0
}
