package com.ytaudio.app

import android.content.Context
import android.webkit.CookieManager
import java.io.File

/** جلسة إنستغرام (كوكيز) تُستخدم فقط لتحميل ما يتطلب تسجيل دخول مثل الستوريات. */
object IgSession {
    private fun file(ctx: Context) = File(ctx.filesDir, "ig_cookies.txt")

    fun isLoggedIn(ctx: Context): Boolean = file(ctx).exists()

    fun cookieFile(ctx: Context): File? = file(ctx).takeIf { it.exists() }

    private fun parse(ctx: Context): Map<String, String> {
        val f = file(ctx)
        if (!f.exists()) return emptyMap()
        val m = LinkedHashMap<String, String>()
        f.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val p = line.split("\t")
            if (p.size >= 7 && p[6].all { c -> c.code in 32..126 }) m[p[5]] = p[6]
        }
        return m
    }

    fun cookieHeader(ctx: Context): String? =
        parse(ctx).takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { "${it.key}=${it.value}" }

    fun csrf(ctx: Context): String? = parse(ctx)["csrftoken"]

    fun saveFromCookieString(ctx: Context, cookies: String) {
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        cookies.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .forEach { c ->
                val name = c.substringBefore("=")
                val value = c.substringAfter("=")
                sb.append(".instagram.com\tTRUE\t/\tTRUE\t").append(expiry)
                    .append('\t').append(name).append('\t').append(value).append('\n')
            }
        file(ctx).writeText(sb.toString())
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}
