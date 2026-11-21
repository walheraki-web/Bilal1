package com.bilal.tv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.bilal.tv.ui.MainActivity
import com.bilal.tv.util.BLog
import com.bilal.tv.util.Prefs

/**
 * Captures one remote key to open Bilal's panel over the live channel — the same
 * mechanism the reference app uses. The user enables this accessibility service
 * once, then "teaches" Bilal which button to use (learn mode): the next key
 * pressed becomes the panel key.
 *
 * Only the chosen key is consumed; every other key passes through untouched, so
 * normal remote navigation of the TV is unaffected.
 */
class BilalKeyService : AccessibilityService() {

    private val prefs by lazy { Prefs(this) }
    private var lastRedDown = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // Never hijack the essential navigation keys.
        if (code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_POWER) return false

        if (prefs.learnPanelKey) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                code != KeyEvent.KEYCODE_BACK
            ) {
                prefs.panelKey = code
                prefs.learnPanelKey = false
                BLog.i("panel key learned: $code")
            }
            return true // swallow both down and up while learning
        }

        // If a key was explicitly learned, a single press of it opens the panel.
        if (prefs.panelKey != -1 && code == prefs.panelKey) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openPanel()
            return true
        }

        // Default shortcut: double-press the RED button.
        if (code == KeyEvent.KEYCODE_PROG_RED) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val now = event.eventTime
                if (now - lastRedDown in 1..DOUBLE_MS) {
                    lastRedDown = 0
                    openPanel()
                } else {
                    lastRedDown = now
                }
            }
            return true // consume red so a stray single press does nothing
        }
        return false
    }

    private companion object {
        const val DOUBLE_MS = 700L
    }

    private fun openPanel() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                ),
            )
        }.onFailure { BLog.e("open panel", it) }
    }
}
