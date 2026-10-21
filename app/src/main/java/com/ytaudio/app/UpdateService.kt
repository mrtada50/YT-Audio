package com.ytaudio.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** ينزّل ملف التحديث بالخلفية (يستمر حتى لو خرجت من التطبيق) ثم يرسل إشعار التثبيت. */
class UpdateService : Service() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_CODE = "code"
        private const val NID_PROGRESS = 900
        private const val NID_RESULT = 901
        private val running = AtomicBoolean(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        Updater.ensureChannels(this)
    }

    private fun progress(p: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, Updater.CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("تنزيل تحديث YT Audio")
            .setContentText(if (indeterminate) "جاري البدء…" else "$p%")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, p, indeterminate)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0

        ServiceCompat.startForeground(
            this, NID_PROGRESS, progress(0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        if (url == null || code == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY // تنزيل جارٍ بالفعل

        scope.launch {
            try {
                Updater.download(this@UpdateService, Updater.Info(code, "", url)) { p ->
                    nm.notify(NID_PROGRESS, progress(p, false))
                }
                Updater.markReady(this@UpdateService, code)
                stopForeground(STOP_FOREGROUND_REMOVE)

                val open = PendingIntent.getActivity(
                    this@UpdateService, 1,
                    Intent(this@UpdateService, MainActivity::class.java)
                        .putExtra("install_update", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                nm.notify(
                    NID_RESULT,
                    NotificationCompat.Builder(this@UpdateService, Updater.CH_READY)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("التحديث جاهز للتثبيت")
                        .setContentText("اضغط هنا لتثبيت الإصدار $code فوق التطبيق الحالي")
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build()
                )
            } catch (e: Exception) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                nm.notify(
                    NID_RESULT,
                    NotificationCompat.Builder(this@UpdateService, Updater.CH_READY)
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setContentTitle("فشل تنزيل التحديث")
                        .setContentText(e.message ?: "خطأ غير معروف")
                        .setAutoCancel(true)
                        .build()
                )
            } finally {
                running.set(false)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
