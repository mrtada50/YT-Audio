package com.ytaudio.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/** تهيئة yt-dlp (تيك توك / إنستغرام) وتحديثه تلقائياً كل 24 ساعة. */
object YtDlpHelper {
    private var ready = false
    private const val DAY = 24L * 60 * 60 * 1000

    @Synchronized
    fun ensureReady(ctx: Context) {
        if (!ready) {
            YoutubeDL.getInstance().init(ctx)
            FFmpeg.getInstance().init(ctx)
            ready = true
        }
        val prefs = ctx.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_update", 0L)
        if (System.currentTimeMillis() - last > DAY) {
            update(ctx)
        }
    }

    @Synchronized
    fun update(ctx: Context) {
        try {
            YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.NIGHTLY)
            ctx.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)
                .edit().putLong("last_update", System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            // لا مشكلة: نكمل بالنسخة الحالية
        }
    }
}
