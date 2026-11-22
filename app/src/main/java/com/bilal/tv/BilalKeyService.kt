package com.bilal.tv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.bilal.tv.ui.MainActivity
import com.bilal.tv.util.Prefs
import kotlin.concurrent.thread

/**
 * Opens Bilal's panel over the live channel from the remote: a double-press of
 * the RED button, or a single press of a key the user "teaches" it.
 *
 * onKeyEvent runs on the input-dispatch path, so it must return almost instantly
 * and never touch disk — otherwise the box's input system stalls (a ~10 s hang).
 * All config is kept in volatile fields in [Config]; disk I/O happens only off
 * this path.
 */
class BilalKeyService : AccessibilityService() {

    /** In-memory config shared with the UI (same process); no disk I/O on the key path. */
    object Config {
        @Volatile var panelKey: Int = -1
        @Volatile var learnMode: Boolean = false

        fun loadFrom(prefs: Prefs) { panelKey = prefs.panelKey }
        fun startLearn() { learnMode = true }
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastRedDown = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching { Config.loadFrom(Prefs(this)) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Fast path only: volatile reads, no disk, no logging, never consume.
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        val code = event.keyCode

        if (Config.learnMode) {
            if (code != KeyEvent.KEYCODE_BACK && code != KeyEvent.KEYCODE_HOME &&
                code != KeyEvent.KEYCODE_POWER
            ) {
                Config.panelKey = code
                Config.learnMode = false
                persistPanelKey(code)
            }
            return false
        }

        val learned = Config.panelKey
        if (learned != -1 && code == learned) {
            openPanel()
            return false
        }

        if (code == KeyEvent.KEYCODE_PROG_RED) {
            val now = event.eventTime
            if (now - lastRedDown in 1..DOUBLE_MS) {
                lastRedDown = 0
                openPanel()
            } else {
                lastRedDown = now
            }
        }
        return false
    }

    private fun persistPanelKey(code: Int) {
        thread(isDaemon = true) { runCatching { Prefs(this@BilalKeyService).panelKey = code } }
    }

    private fun openPanel() {
        main.post {
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    ),
                )
            }
        }
    }

    private companion object {
        const val DOUBLE_MS = 700L
    }
}
