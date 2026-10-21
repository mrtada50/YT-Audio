package com.ytaudio.app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** تحديث من داخل التطبيق عبر آخر Release في مستودع GitHub. */
object Updater {

    const val CH_PROGRESS = "update_progress"
    const val CH_READY = "update_ready"

    class Info(val code: Int, val notes: String, val apkUrl: String)

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "تنزيل التحديث", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_READY, "التحديثات", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)

    fun currentCode(ctx: Context): Int =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    fun apkFile(ctx: Context): File =
        File(File(ctx.cacheDir, "update").apply { mkdirs() }, "YtAudio.apk")

    fun markReady(ctx: Context, code: Int) {
        prefs(ctx).edit().putInt("ready_code", code).apply()
    }

    /** رقم الإصدار الجاهز للتثبيت (تم تنزيله بالكامل)، أو 0. */
    fun readyCode(ctx: Context): Int =
        if (apkFile(ctx).exists()) prefs(ctx).getInt("ready_code", 0) else 0

    fun isReady(ctx: Context, code: Int) = readyCode(ctx) == code

    /** نحذف ملف التحديث بعد أن يُثبَّت. */
    fun cleanupIfInstalled(ctx: Context) {
        val stored = prefs(ctx).getInt("ready_code", 0)
        if (stored != 0 && stored <= currentCode(ctx)) {
            apkFile(ctx).delete()
            prefs(ctx).edit().remove("ready_code").apply()
        }
    }

    /** blocking: استدعها من خيط خلفي. ترجع null إذا لا يوجد تحديث. */
    fun check(ctx: Context): Info? {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) {
            throw IOException("هذه النسخة لم تُبنَ على GitHub Actions، فلا يوجد مستودع للتحديث")
        }
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "YtAudio")
            .build()
        DownloaderImpl.client.newCall(req).execute().use { r ->
            if (r.code == 404) {
                throw IOException("لا يوجد Release منشور بعد، أو المستودع خاص (يجب أن يكون Public)")
            }
            if (!r.isSuccessful) throw IOException("GitHub رد بالخطأ HTTP ${r.code}")
            val j = JSONObject(r.body?.string().orEmpty())
            val code = j.optString("tag_name").filter { it.isDigit() }.toIntOrNull() ?: return null
            if (code <= currentCode(ctx)) return null

            val assets = j.optJSONArray("assets") ?: return null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    url = a.optString("browser_download_url")
                    break
                }
            }
            if (url.isNullOrEmpty()) return null
            return Info(code, j.optString("body").trim(), url)
        }
    }

    /** ينزّل الـ APK إلى ملف مؤقت ثم يعيد تسميته عند الاكتمال فقط. */
    fun download(ctx: Context, info: Info, onProgress: (Int) -> Unit): File {
        val out = apkFile(ctx)
        val part = File(out.parentFile, "YtAudio.apk.part")
        part.delete()
        out.delete()
        prefs(ctx).edit().remove("ready_code").apply()

        val req = Request.Builder().url(info.apkUrl).header("User-Agent", "YtAudio").build()
        DownloaderImpl.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("فشل التنزيل HTTP ${r.code}")
            val body = r.body ?: throw IOException("رد فارغ")
            val total = body.contentLength()
            var done = 0L
            var last = -1
            body.byteStream().use { input ->
                FileOutputStream(part).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) {
                                last = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("انقطع التنزيل قبل الاكتمال")
        }
        if (!part.renameTo(out)) throw IOException("تعذر حفظ ملف التحديث")
        return out
    }

    fun installReady(activity: Activity) {
        val f = apkFile(activity)
        if (!f.exists()) {
            Toast.makeText(activity, "ملف التحديث غير موجود، نزّله من جديد", Toast.LENGTH_LONG).show()
            return
        }
        install(activity, f)
    }

    fun install(activity: Activity, apk: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            Toast.makeText(
                activity,
                "فعّل «السماح من هذا المصدر» ثم اضغط إشعار التحديث أو زر التحديث مرة ثانية",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
