package com.ytaudio.app

import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * بديل لتحميل الصور (بوستات الصور في إنستغرام وتيك توك) عندما لا تدعمها أداة yt-dlp.
 * يعمل للمحتوى العام فقط، وهو "أفضل جهد" لأن الصفحات تتغير.
 */
object ImageFetcher {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    class Found(val id: String, val urls: List<String>)

    fun find(url: String, source: String): Found? = when (source) {
        "Instagram" -> instagram(url)
        "TikTok" -> tiktok(url)
        else -> null
    }

    private fun get(url: String, referer: String? = null): Pair<String, String> {
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "en-US,en;q=0.9")
        if (referer != null) b.header("Referer", referer)
        DownloaderImpl.client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return Pair(r.request.url.toString(), r.body?.string().orEmpty())
        }
    }

    private fun clean(s: String): String =
        s.replace("\\\\/", "/").replace("\\/", "/")
            .replace("\\\\u0026", "&").replace("\\u0026", "&")
            .replace("&amp;", "&").replace("\\", "")

    private fun instagram(url: String): Found? {
        val code = Regex("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
            ?: return null
        val html = get("https://www.instagram.com/p/$code/embed/captioned/").second

        // إذا كان المنشور فيديو فلا نحفظ الغلاف كأنه صورة
        if (Regex("is_video\\\\*\"\\s*:\\s*true").containsMatchIn(html)) return null

        var urls = Regex("display_url\\\\*\"\\s*:\\s*\\\\*\"(https:.+?)\\\\*\"")
            .findAll(html).map { clean(it.groupValues[1]) }.toList().distinct()
        if (urls.isEmpty()) {
            urls = Regex("class=\"EmbeddedMediaImage\"[^>]*src=\"([^\"]+)\"")
                .findAll(html).map { clean(it.groupValues[1]) }.toList().distinct()
        }
        return if (urls.isEmpty()) null else Found(code, urls)
    }

    private fun tiktok(url: String): Found? {
        val (finalUrl, html) = get(url, "https://www.tiktok.com/")
        val id = Regex("/(?:photo|video)/(\\d+)").find(finalUrl)?.groupValues?.get(1) ?: "post"
        val m = Regex(
            "<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>(.*?)</script>",
            RegexOption.DOT_MATCHES_ALL
        ).find(html) ?: return null
        val item = JSONObject(m.groupValues[1])
            .optJSONObject("__DEFAULT_SCOPE__")
            ?.optJSONObject("webapp.video-detail")
            ?.optJSONObject("itemInfo")
            ?.optJSONObject("itemStruct") ?: return null
        val images = item.optJSONObject("imagePost")?.optJSONArray("images") ?: return null
        val urls = (0 until images.length()).mapNotNull { i ->
            images.optJSONObject(i)
                ?.optJSONObject("imageURL")
                ?.optJSONArray("urlList")
                ?.optString(0)
                ?.takeIf { it.isNotBlank() }
        }
        return if (urls.isEmpty()) null else Found(id, urls)
    }

    /** ينزّل صورة واحدة إلى dir ويرجع الملف، أو null عند الفشل. */
    fun download(url: String, dir: File, index: Int, source: String): File? {
        val referer = if (source == "TikTok") "https://www.tiktok.com/" else "https://www.instagram.com/"
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .build()
        DownloaderImpl.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val type = resp.header("Content-Type").orEmpty()
            val ext = when {
                type.contains("mp4") -> "mp4"
                type.contains("quicktime") -> "mov"
                type.contains("webm") -> "webm"
                type.contains("png") -> "png"
                type.contains("webp") -> "webp"
                type.contains("heic") -> "heic"
                else -> "jpg"
            }
            val f = File(dir, "img_$index.$ext")
            FileOutputStream(f).use { out -> body.byteStream().copyTo(out) }
            return f
        }
    }
}
