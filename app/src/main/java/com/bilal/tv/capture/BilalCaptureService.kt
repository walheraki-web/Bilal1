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

    override fun destroy() {
        worker?.interrupt()
        worker = null
    }
}
