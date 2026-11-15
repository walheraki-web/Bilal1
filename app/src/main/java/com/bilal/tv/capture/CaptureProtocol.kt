package com.bilal.tv.capture

/**
 * Wire format between the privileged capture helper ([CaptureMain], run as the
 * shell user through app_process) and the app ([InternalCaptureSource]), over a
 * LocalSocket named [SOCKET].
 *
 * Header, big-endian:
 *   int  magic           ERR or PCM
 *   if ERR: UTF  message
 *   if PCM:
 *     int  version       (1 = no level, 2 = with per-block RMS float)
 *     int  sampleRate
 *     int  channels
 *     int  bitsPerSample (16)
 * Then, repeatedly:
 *   long firstFrameBootMs   (<0 ends the stream, followed by UTF error)
 *   int  byteCount
 *   [float rms]             (version >= 2)
 *   byte[byteCount] pcm
 */
object CaptureProtocol {
    const val SOCKET = "bilal.capture"
    const val MAGIC_PCM = 0x50434d31 // "PCM1"
    const val MAGIC_ERR = 0x45525231 // "ERR1"
    const val SHELL_UID = 2000
    const val BLOCK_BYTES = 3840 // 20 ms stereo 48 kHz 16-bit
}
