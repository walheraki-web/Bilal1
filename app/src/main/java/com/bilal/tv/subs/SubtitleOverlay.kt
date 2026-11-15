package com.bilal.tv.subs

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.bilal.tv.util.BLog

/**
 * A caption bar drawn over the live TV picture using a system overlay window.
 * Final lines are shown steadily then faded; partial lines update in place.
 * Right-to-left, large, high-contrast — readable from a sofa.
 */
class SubtitleOverlay(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var clearRunnable: Runnable? = null

    fun show() {
        main.post {
            if (view != null) return@post
            val tv = TextView(context).apply {
                textSize = 26f
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
                gravity = Gravity.CENTER
                textDirection = View.TEXT_DIRECTION_RTL
                setPadding(48, 24, 48, 24)
                background = GradientDrawable().apply {
                    cornerRadius = 20f
                    setColor(Color.argb(170, 0, 0, 0))
                }
                visibility = View.GONE
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 72
            }
            runCatching { wm.addView(tv, lp); view = tv }
                .onFailure { BLog.e("overlay add", it) }
        }
    }

    fun set(text: String, isFinal: Boolean) {
        main.post {
            val tv = view ?: return@post
            tv.text = text
            tv.visibility = View.VISIBLE
            clearRunnable?.let { main.removeCallbacks(it) }
            if (isFinal) {
                val r = Runnable { tv.visibility = View.GONE }
                clearRunnable = r
                // Keep final lines readable ~ one second per 8 chars, 2.5–7 s.
                main.postDelayed(r, (text.length * 120L).coerceIn(2500, 7000))
            }
        }
    }

    fun hide() {
        main.post {
            view?.let { runCatching { wm.removeView(it) } }
            view = null
        }
    }
}
