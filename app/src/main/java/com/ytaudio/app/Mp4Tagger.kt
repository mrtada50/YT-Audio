package com.ytaudio.app

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * يكتب وسوم (العنوان، الفنان، صورة الغلاف) داخل ملف M4A/MP4
 * عن طريق إضافة udta/meta/ilst داخل moov، مع تصحيح stco/co64 عند الحاجة.
 */
object Mp4Tagger {

    private val L1 = Charsets.ISO_8859_1

    private class Top(val type: String, val start: Long, val size: Long, val hdr: Int)

    fun tag(input: File, output: File, title: String, artist: String, cover: ByteArray?) {
        RandomAccessFile(input, "r").use { raf ->
            val tops = parseTop(raf)
            val moov = tops.firstOrNull { it.type == "moov" } ?: throw IOException("moov not found")
            if (moov.hdr != 8) throw IOException("unsupported moov header")
            val firstMdat = tops.firstOrNull { it.type == "mdat" }

            val moovBytes = ByteArray(moov.size.toInt())
            raf.seek(moov.start)
            raf.readFully(moovBytes)

            val udta = buildUdta(title, artist, cover)

            // نحتفظ بكل أبناء moov ما عدا udta القديم
            val kept = ByteArrayOutputStream()
            var p = 8
            while (p + 8 <= moovBytes.size) {
                val size = ByteBuffer.wrap(moovBytes, p, 4).int
                if (size < 8 || p + size > moovBytes.size) break
                val type = String(moovBytes, p + 4, 4, L1)
                if (type != "udta") kept.write(moovBytes, p, size)
                p += size
            }
            val body = kept.toByteArray()

            val newMoovSize = 8 + body.size + udta.size
            val delta = newMoovSize - moov.size.toInt()

            // إذا كان moov قبل mdat فإن مواضع البيانات تتغير
            if (firstMdat != null && moov.start < firstMdat.start) {
                patchOffsets(body, 0, body.size, delta)
            }

            val newMoov = ByteBuffer.allocate(newMoovSize)
                .putInt(newMoovSize)
                .put("moov".toByteArray(L1))
                .put(body)
                .put(udta)
                .array()

            BufferedOutputStream(FileOutputStream(output)).use { out ->
                for (t in tops) {
                    if (t === moov) out.write(newMoov) else copyRange(raf, t.start, t.size, out)
                }
            }
        }
    }

    private fun parseTop(raf: RandomAccessFile): List<Top> {
        val list = ArrayList<Top>()
        val len = raf.length()
        var pos = 0L
        while (pos + 8 <= len) {
            raf.seek(pos)
            val s = raf.readInt().toLong() and 0xFFFFFFFFL
            val tb = ByteArray(4)
            raf.readFully(tb)
            var size = s
            var hdr = 8
            if (s == 1L) {
                size = raf.readLong()
                hdr = 16
            } else if (s == 0L) {
                size = len - pos
            }
            if (size < hdr) break
            if (pos + size > len) size = len - pos
            list.add(Top(String(tb, L1), pos, size, hdr))
            pos += size
        }
        return list
    }

    private fun copyRange(raf: RandomAccessFile, start: Long, size: Long, out: OutputStream) {
        raf.seek(start)
        val buf = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun patchOffsets(buf: ByteArray, start: Int, end: Int, delta: Int) {
        val bb = ByteBuffer.wrap(buf)
        var p = start
        while (p + 8 <= end) {
            val size = bb.getInt(p)
            if (size < 8 || p + size > end) break
            when (String(buf, p + 4, 4, L1)) {
                "trak", "mdia", "minf", "stbl" -> patchOffsets(buf, p + 8, p + size, delta)
                "stco" -> {
                    val count = bb.getInt(p + 12)
                    var q = p + 16
                    repeat(count) {
                        bb.putInt(q, bb.getInt(q) + delta)
                        q += 4
                    }
                }
                "co64" -> {
                    val count = bb.getInt(p + 12)
                    var q = p + 16
                    repeat(count) {
                        bb.putLong(q, bb.getLong(q) + delta)
                        q += 8
                    }
                }
            }
            p += size
        }
    }

    private fun i32(v: Int): ByteArray = ByteBuffer.allocate(4).putInt(v).array()

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val size = 8 + parts.sumOf { it.size }
        val bb = ByteBuffer.allocate(size)
        bb.putInt(size)
        bb.put(type.toByteArray(L1))
        parts.forEach { bb.put(it) }
        return bb.array()
    }

    private fun dataBox(flags: Int, payload: ByteArray) = box("data", i32(flags), i32(0), payload)

    private fun textItem(type: String, text: String) =
        box(type, dataBox(1, text.toByteArray(Charsets.UTF_8)))

    private fun buildUdta(title: String, artist: String, cover: ByteArray?): ByteArray {
        val items = ArrayList<ByteArray>()
        items.add(textItem("\u00A9nam", title))
        items.add(textItem("\u00A9ART", artist))
        items.add(textItem("aART", artist))
        items.add(textItem("\u00A9alb", title))
        if (cover != null) items.add(box("covr", dataBox(13, cover))) // 13 = JPEG
        val ilst = box("ilst", *items.toTypedArray())
        val hdlr = box(
            "hdlr", i32(0), i32(0),
            "mdir".toByteArray(L1), "appl".toByteArray(L1),
            i32(0), i32(0), byteArrayOf(0)
        )
        val meta = box("meta", i32(0), hdlr, ilst)
        return box("udta", meta)
    }
}
