package com.ytaudio.app

import android.content.Context
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.URLEncoder

class IgRateLimit(message: String) : IOException(message)

/**
 * يجلب روابط الوسائط (صور/فيديو) من واجهة إنستغرام الخاصة بالموقع باستخدام جلسة المستخدم.
 * يدعم: البوستات (صور وألبومات وفيديو)، الستوريات، والهايلايت.
 */
object InstagramApi {
    private const val APP_ID = "936619743392459"
    const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private const val COOLDOWN_MS = 15L * 60 * 1000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ig_api", Context.MODE_PRIVATE)

    /** بعد أي تقييد (429) نوقف الطلبات 15 دقيقة حتى لا نزيد المدة. */
    private fun checkCooldown(ctx: Context) {
        val left = prefs(ctx).getLong("cooldown_until", 0L) - System.currentTimeMillis()
        if (left > 0) {
            throw IgRateLimit("ما زال تقييد إنستغرام ساريًا. انتظر ${left / 60000 + 1} دقيقة تقريبًا قبل المحاولة")
        }
    }

    class MediaItem(val url: String, val isVideo: Boolean)
    class Resolved(val label: String, val items: List<MediaItem>)

    fun resolve(ctx: Context, url: String): Resolved? {
        Regex("/stories/highlights/(\\d+)").find(url)?.let { m ->
            val id = m.groupValues[1]
            return Resolved(
                "highlight $id",
                reels(ctx, "highlight:$id", null, "https://www.instagram.com/stories/highlights/$id/")
            )
        }
        Regex("/stories/([A-Za-z0-9._]+)(?:/(\\d+))?").find(url)?.let { m ->
            val user = m.groupValues[1]
            val only = m.groupValues[2].ifEmpty { null }
            if (only != null) {
                // رابط ستوري محدد: طلب واحد فقط بدون البحث عن الحساب
                try {
                    val direct = mediaInfo(ctx, only, "https://www.instagram.com/stories/$user/$only/")
                    if (direct.isNotEmpty()) return Resolved("story $user", direct)
                } catch (e: IOException) {
                    if (e is IgRateLimit || e.message == "LOGIN") throw e
                }
            }
            val uid = userId(ctx, user)
            return Resolved("story $user", reels(ctx, uid, only, "https://www.instagram.com/stories/$user/"))
        }
        Regex("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)").find(url)?.let { m ->
            val code = m.groupValues[1]
            return Resolved(code, post(ctx, code))
        }
        return null
    }

    private fun shortcodeToId(code: String): String {
        val c = if (code.length > 11) code.substring(0, 11) else code
        var id = BigInteger.ZERO
        for (ch in c) {
            id = id.multiply(BigInteger.valueOf(64))
                .add(BigInteger.valueOf(ALPHABET.indexOf(ch).toLong()))
        }
        return id.toString()
    }

    private fun post(ctx: Context, code: String): List<MediaItem> =
        mediaInfo(ctx, shortcodeToId(code), "https://www.instagram.com/p/$code/")

    private fun mediaInfo(ctx: Context, id: String, referer: String): List<MediaItem> {
        val j = getJson(ctx, "https://www.instagram.com/api/v1/media/$id/info/", referer)
        val items = j.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<MediaItem>()
        for (i in 0 until items.length()) parseItem(items.getJSONObject(i), out)
        return out
    }

    private fun userId(ctx: Context, username: String): String {
        val key = "uid_" + username.lowercase()
        prefs(ctx).getString(key, null)?.let { return it }
        val j = getJson(
            ctx,
            "https://www.instagram.com/api/v1/users/web_profile_info/?username=" +
                URLEncoder.encode(username, "UTF-8"),
            "https://www.instagram.com/$username/"
        )
        val id = j.optJSONObject("data")?.optJSONObject("user")?.optString("id")
            ?.takeIf { it.isNotEmpty() }
            ?: throw IOException("لم يتم العثور على الحساب")
        prefs(ctx).edit().putString(key, id).apply()
        return id
    }

    private fun reels(ctx: Context, reelId: String, onlyId: String?, referer: String): List<MediaItem> {
        val j = getJson(
            ctx,
            "https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=" + URLEncoder.encode(reelId, "UTF-8"),
            referer
        )
        val reel = j.optJSONObject("reels")?.optJSONObject(reelId)
            ?: j.optJSONArray("reels_media")?.optJSONObject(0)
        val items = reel?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<MediaItem>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (onlyId != null) {
                val pk = item.optString("pk")
                val id = item.optString("id")
                if (pk != onlyId && !id.startsWith(onlyId + "_")) continue
            }
            parseItem(item, out)
        }
        return out
    }

    private fun parseItem(o: JSONObject, out: MutableList<MediaItem>) {
        val carousel = o.optJSONArray("carousel_media")
        if (carousel != null) {
            for (i in 0 until carousel.length()) parseItem(carousel.getJSONObject(i), out)
            return
        }
        val videos = o.optJSONArray("video_versions")
        if (videos != null && videos.length() > 0) {
            out.add(MediaItem(videos.getJSONObject(0).getString("url"), true))
            return
        }
        val cands = o.optJSONObject("image_versions2")?.optJSONArray("candidates")
        if (cands != null && cands.length() > 0) {
            out.add(MediaItem(cands.getJSONObject(0).getString("url"), false))
        }
    }

    private fun getJson(ctx: Context, url: String, referer: String): JSONObject {
        checkCooldown(ctx)
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("X-IG-App-ID", APP_ID)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "*/*")
            .header("Referer", referer)
            .header("Origin", "https://www.instagram.com")
            .header("X-ASBD-ID", "198387")
            .header("X-IG-WWW-Claim", "0")
        IgSession.cookieHeader(ctx)?.let { b.header("Cookie", it) }
        IgSession.csrf(ctx)?.let { b.header("X-CSRFToken", it) }

        DownloaderImpl.client.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403 ||
                text.contains("login_required") || text.contains("checkpoint_required") ||
                text.contains("require_login")
            ) {
                throw IOException("LOGIN")
            }
            if (r.code == 429) {
                prefs(ctx).edit().putLong("cooldown_until", System.currentTimeMillis() + COOLDOWN_MS).apply()
                throw IgRateLimit(
                    "إنستغرام قيّد الطلبات مؤقتًا. انتظر 15 دقيقة على الأقل ولا تكرر المحاولة. " +
                        "إذا تكرر بعد الانتظار فسجّل الخروج والدخول من جديد"
                )
            }
            if (!r.isSuccessful) throw IOException("إنستغرام رد بالخطأ HTTP ${r.code}")
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IOException("LOGIN")
            }
        }
    }
}
