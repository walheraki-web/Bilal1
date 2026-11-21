package com.bilal.tv.capture

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.bilal.tv.util.BLog
import rikka.shizuku.Shizuku

/**
 * Obtains shell-level privilege through Shizuku and starts the internal audio
 * capture service in that privileged process. Shizuku is activated once by the
 * user over ADB (or root); afterwards Bilal can capture the receiver's audio
 * output that a sandboxed app cannot reach.
 */
object ShizukuCapture {

    private const val REQ = 7001
    private var args: Shizuku.UserServiceArgs? = null
    private var conn: ServiceConnection? = null

    /** True when the Shizuku service is running and reachable. */
    fun isAvailable(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Ensures Shizuku permission, then calls [onReady]; otherwise [onError]. */
    fun ensurePermission(onReady: () -> Unit, onError: (String) -> Unit) {
        if (!isAvailable()) {
            onError("Shizuku غير مُفعّل على الجهاز")
            return
        }
        if (Shizuku.isPreV11()) {
            onError("إصدار Shizuku قديم؛ حدّثه")
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            onReady()
            return
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            onError("رُفض إذن Shizuku من قبل")
            return
        }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                Shizuku.removeRequestPermissionResultListener(this)
                if (requestCode == REQ && grantResult == PackageManager.PERMISSION_GRANTED) onReady()
                else onError("لم يُمنح إذن Shizuku")
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        runCatching { Shizuku.requestPermission(REQ) }
            .onFailure {
                Shizuku.removeRequestPermissionResultListener(listener)
                onError("تعذّر طلب إذن Shizuku: ${it.message}")
            }
    }

    /** Binds the privileged capture service and starts it. */
    fun bindAndStart(context: Context, onStatus: (String) -> Unit, onError: (String) -> Unit) {
        val a = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, BilalCaptureService::class.java.name),
        ).daemon(false).processNameSuffix("cap").debuggable(false).version(1)
        args = a
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                runCatching {
                    val svc = IBilalCapture.Stub.asInterface(binder)
                    svc.startCapture()
                    BLog.i("shizuku capture service started")
                    onStatus("التقاط صوت الريسيفر يعمل (Shizuku)")
                }.onFailure { onError("فشل بدء الالتقاط: ${it.message}") }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                BLog.i("shizuku capture service disconnected")
            }
        }
        conn = c
        runCatching { Shizuku.bindUserService(a, c) }
            .onFailure { onError("فشل الربط بـ Shizuku: ${it.message}") }
    }

    fun stop() {
        val a = args ?: return
        val c = conn ?: return
        runCatching { Shizuku.unbindUserService(a, c, true) }
        conn = null
    }
}
