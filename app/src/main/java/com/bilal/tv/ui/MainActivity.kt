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
import com.bilal.tv.util.Playlist
import com.bilal.tv.util.Prefs
import com.bilal.tv.util.Station

/**
 * Minimal remote-friendly UI: pick/add a station, Play, Sync, toggle movie
 * subtitles, and set the recovery options. Built in code so there is one place
 * to read the whole screen.
 */
@UnstableApi
class MainActivity : android.app.Activity(), BilalService.Listener {

    private companion object {
        const val REQ_PICK_PLAYLIST = 91
    }

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
        com.bilal.tv.BilalKeyService.Config.loadFrom(prefs)
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
            setPadding(24, 16, 24, 16)
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

        // Station picker is created here but attached LAST (it can be long after
        // a CFG import), so the controls above stay reachable without scrolling
        // past dozens of stations.
        stationsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        refreshStations()

        panel.addView(section("زر فتح اللوحة على الريموت"))
        panel.addView(button("تفعيل خدمة الزر (إمكانية الوصول)") {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { Toast.makeText(this, "افتح: الإعدادات ▸ إمكانية الوصول ▸ Bilal", Toast.LENGTH_LONG).show() }
        })
        panel.addView(button("تعليم زر آخر: اضغط بعدها الزر على الريموت") {
            com.bilal.tv.BilalKeyService.Config.startLearn()
            Toast.makeText(this, "اضغط الآن الزر الذي تريده لفتح اللوحة", Toast.LENGTH_LONG).show()
        })
        panel.addView(button("إلغاء الزر المتعلَّم (العودة للأحمر مرتين)") {
            prefs.panelKey = -1
            com.bilal.tv.BilalKeyService.Config.panelKey = -1
            Toast.makeText(this, "الاختصار الآن: الزر الأحمر مرتين", Toast.LENGTH_SHORT).show()
        })
        panel.addView(label("الافتراضي: الزر الأحمر مرتين سريعًا يفتح اللوحة فوق القناة. فعّل خدمة الزر أولًا."))

        panel.addView(section("مصدر صوت الريسيفر"))
        panel.addView(button("التقاط مباشر (بلا Shizuku)") { service?.enableDirectReceiverCapture() })
        panel.addView(button("التقاط عبر Shizuku") { service?.enableReceiverCapture() })
        panel.addView(label("جرّب «التقاط مباشر» أولًا بعد منح Bilal صلاحية التقاط الصوت من مدير صلاحيات الصندوق. إن فشل، استخدم Shizuku."))

        panel.addView(section("ترجمة الفيلم (ملف SRT)"))
        panel.addView(subtitleRows())

        panel.addView(button("تصدير السجل") {
            runCatching { Toast.makeText(this, "حُفظ: ${BLog.export(this)}", Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(this, "تعذّر الحفظ: ${it.message}", Toast.LENGTH_LONG).show() }
        })

        // Station list last — longest section after a CFG import.
        panel.addView(section("المحطة (قناة الصوت) — اختر واحدة"))
        panel.addView(addStationRow())
        panel.addView(importRow())
        panel.addView(stationsBox)

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(panel)
        }
        // Panel pinned to the right, ~58% width, full height — live video at left.
        val lp = FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.58f).toInt(),
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

    private fun importRow(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Browse the USB flash drive / storage and pick a file.
        box.addView(button("اختر ملف من الفلاشة (cfg / m3u)") {
            runCatching {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("*/*"))
                }
                startActivityForResult(intent, REQ_PICK_PLAYLIST)
            }.onFailure {
                Toast.makeText(this, "تعذّر فتح متصفّح الملفات: ${it.message}", Toast.LENGTH_LONG).show()
            }
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val src = EditText(this).apply {
            hint = "أو رابط/مسار ملف m3u/cfg"
            setText(prefs.sp.getString("lastImport", "") ?: "")
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        }
        row.addView(src)
        row.addView(button("استيراد") {
            val source = src.text.toString().trim()
            if (source.isEmpty()) {
                Toast.makeText(this, "اختر ملفًا من الفلاشة، أو أدخل رابط/مسار", Toast.LENGTH_SHORT).show()
                return@button
            }
            prefs.sp.edit().putString("lastImport", source).apply()
            Toast.makeText(this, "جارٍ الاستيراد…", Toast.LENGTH_SHORT).show()
            importStations {
                if (source.startsWith("http", true)) {
                    val conn = (java.net.URL(source).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 15000; readTimeout = 20000; instanceFollowRedirects = true
                    }
                    conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                } else {
                    java.io.File(source).readText(Charsets.UTF_8)
                }
            }
        })
        box.addView(row)
        return box
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_PLAYLIST && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            Toast.makeText(this, "جارٍ قراءة الملف…", Toast.LENGTH_SHORT).show()
            importStations {
                contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }
    }

    /** Reads raw playlist text via [read] on a background thread, then merges. */
    private fun importStations(read: () -> String) {
        Thread {
            val result = runCatching { Playlist.parse(read()) }
            runOnUiThread {
                result.onSuccess { parsed ->
                    if (parsed.isEmpty()) {
                        Toast.makeText(this, "لم يُعثر على محطات في الملف", Toast.LENGTH_LONG).show()
                    } else {
                        val existing = prefs.stations
                        val existingUrls = existing.map { it.url }.toHashSet()
                        val merged = existing + parsed.filterNot { it.url in existingUrls }
                        prefs.stations = merged
                        refreshStations()
                        Toast.makeText(this, "أُضيفت ${merged.size - existing.size} محطة (الإجمالي ${merged.size})", Toast.LENGTH_LONG).show()
                    }
                }.onFailure {
                    Toast.makeText(this, "تعذّر الاستيراد: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { isDaemon = true }.start()
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
        text = t; textSize = 20f; setTextColor(Color.parseColor("#2BB673")); setPadding(0, 0, 0, 10)
    }

    private fun section(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(Color.parseColor("#8FB7A8")); setPadding(0, 16, 0, 4)
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(Color.parseColor("#C8D8D2")); setPadding(0, 2, 0, 2)
    }

    private fun buttonBg(focused: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 14f
        setColor(Color.parseColor(if (focused) "#2BB673" else "#1C2C27"))
        setStroke(if (focused) 5 else 3, Color.parseColor(if (focused) "#8CF2C2" else "#2BB673"))
    }

    private fun button(t: String, onClick: (View) -> Unit) = Button(this).apply {
        text = t; isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        background = buttonBg(false)
        gravity = Gravity.CENTER
        minHeight = 0
        minimumHeight = 0
        setPadding(22, 12, 22, 12)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(6, 6, 6, 6) }
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
