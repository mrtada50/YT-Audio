package com.ytaudio.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryItem(
    val id: Long,
    val title: String,
    val source: String,   // YouTube / TikTok / Instagram
    val kind: String,     // audio / video / image
    val uri: String,
    val time: Long
)

/** سجل بسيط بالملفات المحمّلة، يُحفظ في ملف JSON داخل التطبيق. */
object History {
    private const val FILE = "history.json"
    private val lock = Any()

    @Volatile
    var listener: (() -> Unit)? = null

    fun load(ctx: Context): List<HistoryItem> {
        synchronized(lock) {
            val f = File(ctx.filesDir, FILE)
            if (!f.exists()) return emptyList()
            return try {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    HistoryItem(
                        o.getLong("id"), o.getString("title"), o.getString("source"),
                        o.getString("kind"), o.getString("uri"), o.getLong("time")
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun add(ctx: Context, item: HistoryItem) {
        synchronized(lock) {
            save(ctx, listOf(item) + load(ctx))
        }
        listener?.invoke()
    }

    fun remove(ctx: Context, id: Long) {
        synchronized(lock) {
            save(ctx, load(ctx).filter { it.id != id })
        }
        listener?.invoke()
    }

    private fun save(ctx: Context, list: List<HistoryItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("title", it.title).put("source", it.source)
                    .put("kind", it.kind).put("uri", it.uri).put("time", it.time)
            )
        }
        File(ctx.filesDir, FILE).writeText(arr.toString())
    }
}
