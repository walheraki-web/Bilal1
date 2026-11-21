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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 32)
            setBackgroundColor(Color.parseColor("#0E1A17"))
        }
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        root.addView(title("Bilal — $version"))

        status = label("—")
        result = label(prefs.sp.getString("lastSyncResult", "لم تُجرَ مزامنة بعد")!!)
        root.addView(result)
        root.addView(status)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("مزامنة الآن") { service?.sync() })
        controls.addView(button("إيقاف") { service?.stopPlayback() })
        root.addView(controls)

        root.addView(section("مصدر صوت الريسيفر"))
        root.addView(button("تفعيل التقاط صوت الريسيفر (Shizuku)") { service?.enableReceiverCapture() })
        root.addView(label("يحتاج تفعيل Shizuku مرة واحدة عبر ADB. بدونه يُستخدم الميكروفون (غير متوفر على أغلب الرسيفرات)."))

        root.addView(section("المحطات"))
        stationsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(stationsBox)
        refreshStations()
        root.addView(addStationRow())

        root.addView(section("خيارات الاستمرارية"))
        root.addView(check("إعادة المزامنة تلقائيًا بعد انقطاع البث", prefs.resyncAfterDrop) { prefs.resyncAfterDrop = it })
        root.addView(check("إعادة المزامنة عند تغيير القناة", prefs.resyncOnChannel) { prefs.resyncOnChannel = it })
        root.addView(check("كتم صوت القناة بعد المزامنة", prefs.muteTvAfterSync) { prefs.muteTvAfterSync = it })
        root.addView(check("التحكم بتأخير الصورة في الريسيفر", prefs.controlVideoDelay) { prefs.controlVideoDelay = it })

        root.addView(section("ترجمة الفيلم (ملف SRT)"))
        root.addView(subtitleRows())

        root.addView(button("تصدير السجل إلى التنزيلات") {
            runCatching { Toast.makeText(this, "حُفظ: ${BLog.export(this)}", Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(this, "تعذّر الحفظ: ${it.message}", Toast.LENGTH_LONG).show() }
        })

        val scroll = ScrollView(this)
        scroll.addView(root)
        return scroll
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
