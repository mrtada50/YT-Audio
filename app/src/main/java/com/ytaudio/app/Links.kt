package com.ytaudio.app

import android.net.Uri

object Links {
    private val hosts = listOf("youtube.com", "youtu.be", "tiktok.com", "instagram.com", "instagr.am")

    /** يرجع أول رابط مدعوم داخل النص، أو null. */
    fun find(text: String): String? {
        val url = Regex("https?://\\S+").find(text)?.value ?: return null
        val host = Uri.parse(url).host.orEmpty()
        return if (hosts.any { host.endsWith(it) }) url else null
    }
}
