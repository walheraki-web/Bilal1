package com.bilal.tv.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import com.bilal.tv.BilalService
import com.bilal.tv.util.BLog
import com.bilal.tv.util.Prefs
import com.bilal.tv.util.Station

/**
 * Minimal remote-friendly UI: pick/add a station, Play, Sync, toggle movie
 * subtitles, and set the recovery options. Built in code so there is one place
 * to read the whole screen.
 */
@UnstableApi
class MainActivity : android.app.Activity(), BilalService.Listener {

    private lateinit var prefs: Prefs
    private var service: BilalService? = null
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var stationsBox: LinearLayout

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            service = (b as BilalService.LocalBinder).service.also { it.listener = this@MainActivity }
            refreshResult()
            refreshValues()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        BLog.init(this)
        setContentView(buildUi())
        requestRuntimePermissions()
        ensureOverlayPermission()
        val intent = Intent(this, BilalService::class.java)
        startForegroundService(intent)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    private fun requestRuntimePermissions() {
        val need = mutableListOf<String>()
        val wanted = listOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        for (p in wanted) {
            if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) need += p
        }
        if (need.isNotEmpty()) runCatching { requestPermissions(need.toTypedArray(), 42) }
    }

    override fun onDestroy() {
        service?.listener = null
        runCatching { unbindService(conn) }
        super.onDestroy()
    }

    // --- UI ---

    private fun buildUi(): View {
        // Transparent frame; the live TV video plane shows through the left side.
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            // Semi-transparent so the panel reads over live video, like ZHE.
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#E60E1A17"))
                cornerRadius = 18f
            }
        }

        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        panel.addView(title("Bilal — $version"))

        result = label(prefs.sp.getString("lastSyncResult", "لم تُجرَ مزامنة بعد")!!)
        status = label("—")
        panel.addView(result)
        panel.addView(status)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("مزامنة الآن") { service?.sync() })
        controls.addView(button("إيقاف") { service?.stopPlayback() })
        panel.addView(controls)

        // المسموع الآن: صوت القناة / الصوتية
        panel.addView(button("المسموع: بدّل بين القناة والصوتية") {
            service?.toggleMuteTv(); refreshValues()
        })

        // تأخير الصورة / تأخير الصوت — exactly the ZHE controls
        videoDelayRow = delayRow("تأخير الصورة", { service?.videoDelayMs ?: 0 },
            onMinus = { service?.nudgeVideoDelay(-500); refreshValues() },
            onPlus = { service?.nudgeVideoDelay(+500); refreshValues() })
        panel.addView(videoDelayRow!!.view)

        audioDelayRow = delayRow("تأخير الصوت", { service?.audioDelayMs ?: 0 },
            onMinus = { service?.nudgeAudioDelay(-250); refreshValues() },
            onPlus = { service?.nudgeAudioDelay(+250); refreshValues() })
        panel.addView(audioDelayRow!!.view)

        panel.addView(check("المزامنة التلقائية بعد الانقطاع", prefs.resyncAfterDrop) {
            service?.autoResync = it
        })

        panel.addView(section("المحطة (قناة الصوت)"))
        stationsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(stationsBox)
        refreshStations()
        panel.addView(addStationRow())

        panel.addView(section("زر فتح اللوحة على الريموت"))
        panel.addView(button("تفعيل خدمة الزر (إمكانية الوصول)") {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { Toast.makeText(this, "افتح: الإعدادات ▸ إمكانية الوصول ▸ Bilal", Toast.LENGTH_LONG).show() }
        })
        panel.addView(button("تعليم الزر: اضغط بعدها الزر على الريموت") {
            prefs.learnPanelKey = true
            val cur = if (prefs.panelKey != -1) " (الحالي: ${prefs.panelKey})" else ""
            Toast.makeText(this, "اضغط الآن الزر الذي تريده لفتح اللوحة$cur", Toast.LENGTH_LONG).show()
        })
        panel.addView(label("فعّل الخدمة مرة واحدة، ثم «تعليم الزر» واضغط أي زر فارغ على الريموت (مثل الزر الأحمر). بعدها يفتح اللوحة فوق القناة."))

        panel.addView(section("مصدر صوت الريسيفر"))
        panel.addView(button("تفعيل التقاط صوت الريسيفر (Shizuku)") { service?.enableReceiverCapture() })

        panel.addView(section("ترجمة الفيلم (ملف SRT)"))
        panel.addView(subtitleRows())

        panel.addView(button("تصدير السجل") {
            runCatching { Toast.makeText(this, "حُفظ: ${BLog.export(this)}", Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(this, "تعذّر الحفظ: ${it.message}", Toast.LENGTH_LONG).show() }
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(panel)
        }
        // Panel pinned to the right, ~48% width, full height — live video at left.
        val lp = FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.50f).toInt(),
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.END }
        frame.addView(scroll, lp)
        return frame
    }

    private class DelayRow(val view: View, val value: TextView, val read: () -> Int)
    private var videoDelayRow: DelayRow? = null
    private var audioDelayRow: DelayRow? = null

    private fun delayRow(label: String, read: () -> Int, onMinus: () -> Unit, onPlus: () -> Unit): DelayRow {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val value = TextView(this).apply {
            text = "0.0 ث"; setTextColor(Color.parseColor("#8CF2C2")); textSize = 17f
            gravity = Gravity.CENTER; minWidth = 160
        }
        row.addView(name)
        row.addView(button("−") { onMinus() })
        row.addView(value)
        row.addView(button("+") { onPlus() })
        return DelayRow(row, value, read)
    }

    private fun refreshValues() = runOnUiThread {
        videoDelayRow?.let { it.value.text = "${it.read() / 1000.0} ث" }
        audioDelayRow?.let { it.value.text = "${it.read() / 1000.0} ث" }
    }

    private fun refreshStations() {
        stationsBox.removeAllViews()
        val stations = prefs.stations
        if (stations.isEmpty()) {
            stationsBox.addView(label("لا توجد محطات بعد. أضف محطة برابط بثّ (m3u8 أو mp3) من الأسفل."))
            return
        }
        for (s in stations) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button(s.name) { service?.play(s) }.also {
                (it.layoutParams as LinearLayout.LayoutParams).weight = 1f
            })
            if (prefs.stations.contains(s)) {
                row.addView(button("حذف") {
                    prefs.stations = prefs.stations.filterNot { it == s }
                    refreshStations()
                })
            }
            stationsBox.addView(row)
        }
    }

    private fun addStationRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val name = EditText(this).apply { hint = "اسم المحطة"; setTextColor(Color.WHITE) }
        val url = EditText(this).apply { hint = "رابط البث (m3u8 أو mp3)"; setTextColor(Color.WHITE) }
        name.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        url.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        row.addView(name); row.addView(url)
        row.addView(button("إضافة") {
            val n = name.text.toString().trim(); val u = url.text.toString().trim()
            if (n.isNotEmpty() && u.isNotEmpty()) {
                prefs.stations = prefs.stations + Station(n, u)
                name.setText(""); url.setText(""); refreshStations()
            }
        })
        return row
    }

    private fun subtitleRows(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val loadRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val srtUrl = EditText(this).apply {
            hint = "رابط ملف SRT، أو مسار ملف على الجهاز"
            setText(prefs.subtitleSource ?: "")
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        }
        loadRow.addView(srtUrl)
        loadRow.addView(button("تحميل") {
            val src = srtUrl.text.toString().trim()
            if (src.isNotEmpty()) { prefs.subtitleSource = src; service?.loadSubtitles(src) }
        })
        box.addView(loadRow)

        val playRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playRow.addView(button("ابدأ الترجمة") { service?.startSubtitles(0) })
        playRow.addView(button("أوقف") { service?.stopSubtitles() })
        playRow.addView(button("−٥ث") { service?.nudgeSubtitles(-5000) })
        playRow.addView(button("−٠٫٥") { service?.nudgeSubtitles(-500) })
        playRow.addView(button("+٠٫٥") { service?.nudgeSubtitles(500) })
        playRow.addView(button("+٥ث") { service?.nudgeSubtitles(5000) })
        box.addView(playRow)

        box.addView(label("حمّل ملف SRT، شغّل الفيلم، ثم اضغط «ابدأ الترجمة» عند بدايته. اضبط التأخير بأزرار الإزاحة حتى تتطابق."))
        return box
    }

    // --- service callbacks ---

    override fun onStatus(text: String) = runOnUiThread { status.text = text }
    override fun onResult(text: String) = runOnUiThread {
        result.text = text
        prefs.sp.edit().putString("lastSyncResult", text).apply()
    }

    private fun refreshResult() = runOnUiThread { result.text = service?.lastResult ?: result.text }

    private fun ensureOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
            }
        }
    }

    // --- tiny view helpers ---

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 30f; setTextColor(Color.parseColor("#2BB673")); setPadding(0, 0, 0, 16)
    }

    private fun section(t: String) = TextView(this).apply {
        text = t; textSize = 18f; setTextColor(Color.parseColor("#8FB7A8")); setPadding(0, 28, 0, 8)
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(Color.WHITE); setPadding(0, 4, 0, 4)
    }

    private fun buttonBg(focused: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 14f
        setColor(Color.parseColor(if (focused) "#2BB673" else "#1C2C27"))
        setStroke(if (focused) 5 else 3, Color.parseColor(if (focused) "#8CF2C2" else "#2BB673"))
    }

    private fun button(t: String, onClick: (View) -> Unit) = Button(this).apply {
        text = t; isAllCaps = false
        textSize = 18f
        setTextColor(Color.WHITE)
        background = buttonBg(false)
        gravity = Gravity.CENTER
        minHeight = 120
        setPadding(36, 24, 36, 24)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(10, 10, 10, 10) }
        layoutParams = lp
        isFocusable = true
        isFocusableInTouchMode = false
        // Bright highlight so the TV remote's position is unmistakable.
        setOnFocusChangeListener { v, hasFocus -> v.background = buttonBg(hasFocus) }
        setOnClickListener(onClick)
    }

    private fun check(t: String, initial: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = t; isChecked = initial; setTextColor(Color.WHITE)
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
}
