package com.ytaudio.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

/** يفحص التحديثات دورياً (كل 12 ساعة) حتى لو التطبيق مغلق، ويرسل إشعاراً إذا وجد نسخة أحدث. */
class UpdateCheckWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        try {
            Updater.cleanupIfInstalled(ctx)
            val info = Updater.check(ctx) ?: return Result.success()

            val prefs = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
            if (prefs.getInt("notified_code", 0) == info.code) return Result.success()
            prefs.edit().putInt("notified_code", info.code).apply()

            Updater.ensureChannels(ctx)
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

            val ready = Updater.isReady(ctx, info.code)
            val details = PendingIntent.getActivity(
                ctx, 2,
                Intent(ctx, MainActivity::class.java)
                    .putExtra(if (ready) "install_update" else "show_update", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                flags
            )
            val b = NotificationCompat.Builder(ctx, Updater.CH_READY)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("تحديث جديد لـ YT Audio (إصدار ${info.code})")
                .setContentText(
                    if (ready) "التحديث جاهز، اضغط للتثبيت"
                    else info.notes.lineSequence().firstOrNull { it.isNotBlank() } ?: "اضغط للتفاصيل"
                )
                .setContentIntent(details)
                .setAutoCancel(true)
            if (!ready) {
                val download = PendingIntent.getForegroundService(
                    ctx, 3,
                    Intent(ctx, UpdateService::class.java)
                        .putExtra(UpdateService.EXTRA_URL, info.apkUrl)
                        .putExtra(UpdateService.EXTRA_CODE, info.code),
                    flags
                )
                b.addAction(0, "تنزيل بالخلفية", download)
            }
            nm.notify(902, b.build())
        } catch (e: Exception) {
            // لا مشكلة: نحاول بالدورة القادمة
        }
        return Result.success()
    }
}
