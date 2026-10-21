package com.ytaudio.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

class DownloadService : Service() {

    companion object {
        const val EXTRA_URL = "url"
        private const val CH = "yt_audio_dl"
        private const val CHUNK = 10L * 1024 * 1024
        private val MEDIA_EXT = setOf(
            "mp4", "m4v", "mov", "webm", "mkv",
            "jpg", "jpeg", "png", "webp", "heic",
            "m4a", "mp3"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicInteger(0)
    private val ids = AtomicInteger(100)
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "التحميلات", NotificationManager.IMPORTANCE_LOW)
        )
        NewPipe.init(DownloaderImpl.instance)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val base: Notification = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("YT Audio")
            .setContentText("جاري التحميل…")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, base, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null) {
            if (active.get() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        active.incrementAndGet()
        val nid = ids.incrementAndGet()
        scope.launch {
            try {
                process(url, nid)
            } catch (e: Throwable) {
                finalNotif(nid, "فشل التحميل", friendly(e, url), null)
            } finally {
                if (active.decrementAndGet() == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------

    private fun process(url: String, nid: Int) {
        val host = Uri.parse(url).host.orEmpty()
        when {
            host.endsWith("youtube.com") || host.endsWith("youtu.be") -> processYoutube(url, nid)
            host.endsWith("tiktok.com") -> processYtDlp(url, nid, "TikTok")
            host.endsWith("instagram.com") || host.endsWith("instagr.am") ->
                processYtDlp(url, nid, "Instagram")
            else -> throw IOException("رابط غير مدعوم")
        }
    }

    // ------------------------- يوتيوب (صوت M4A) ---------------------

    private fun processYoutube(url: String, nid: Int) {
        progressNotif(nid, "جاري قراءة الرابط…", -1)

        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val stream = info.audioStreams
            .filter { it.format == MediaFormat.M4A && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.averageBitrate }
            ?: throw IOException("لا توجد صيغة M4A لهذا المقطع")

        val title: String = info.name ?: "YouTube"
        val artist: String = info.uploaderName ?: "YouTube"
        val cover = fetchCover(info.thumbnails.maxByOrNull { it.height }?.url)

        val raw = File.createTempFile("dl_", ".m4a", cacheDir)
        val tagged = File(cacheDir, "tagged_${System.nanoTime()}.m4a")
        try {
            var last = -1
            download(stream.content, raw) { p ->
                if (p != last) {
                    last = p
                    progressNotif(nid, title, p)
                }
            }
            progressNotif(nid, "جاري الحفظ…", -1)

            val toSave = try {
                Mp4Tagger.tag(raw, tagged, title, artist, cover)
                tagged
            } catch (e: Exception) {
                raw // نحفظ الملف بدون وسوم إذا فشلت الكتابة
            }
            val (uri, kind) = saveMedia(toSave, title, title, artist)
            History.add(
                this,
                HistoryItem(System.nanoTime(), title, "YouTube", kind, uri.toString(), System.currentTimeMillis())
            )
            finalNotif(nid, "تم التحميل ✓", title, uri)
        } finally {
            raw.delete()
            tagged.delete()
        }
    }

    private fun download(url: String, out: File, onProgress: (Int) -> Unit) {
        val client = DownloaderImpl.client
        var start = 0L
        var total = -1L
        var done = false
        FileOutputStream(out).use { fos ->
            while (!done) {
                val req = Request.Builder().url(url)
                    .header("User-Agent", DownloaderImpl.USER_AGENT)
                    .header("Range", "bytes=$start-${start + CHUNK - 1}")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 416 && start > 0) {
                        done = true
                        return@use
                    }
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("رد فارغ من السيرفر")
                    if (resp.code == 206) {
                        if (total < 0) {
                            total = resp.header("Content-Range")
                                ?.substringAfter('/')?.toLongOrNull() ?: -1L
                        }
                        val n = copy(body.byteStream(), fos)
                        start += n
                        if (n == 0L || (total >= 0 && start >= total)) done = true
                    } else {
                        total = body.contentLength()
                        start += copy(body.byteStream(), fos)
                        done = true
                    }
                    if (total > 0) onProgress((start * 100 / total).toInt().coerceIn(0, 100))
                }
            }
        }
    }

    private fun copy(input: InputStream, out: FileOutputStream): Long {
        val buf = ByteArray(32 * 1024)
        var n = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            n += r
        }
        return n
    }

    private fun fetchCover(url: String?): ByteArray? {
        if (url == null) return null
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", DownloaderImpl.USER_AGENT).build()
            val bytes = DownloaderImpl.client.newCall(req).execute().use { it.body?.bytes() }
                ?: return null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val side = minOf(bmp.width, bmp.height)
            var sq = Bitmap.createBitmap(
                bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side
            )
            if (side > 720) sq = Bitmap.createScaledBitmap(sq, 720, 720, true)
            val bos = ByteArrayOutputStream()
            sq.compress(Bitmap.CompressFormat.JPEG, 90, bos)
            bos.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    // ------------------- تيك توك / إنستغرام (yt-dlp) ----------------

    private fun processYtDlp(url: String, nid: Int, source: String) {
        val isIg = source == "Instagram"

        // الستوريات: عبر واجهة إنستغرام مباشرة (تتطلب تسجيل الدخول)
        if (isIg && url.contains("/stories/")) {
            if (!IgSession.isLoggedIn(applicationContext)) throw IOException("LOGIN")
            progressNotif(nid, "جاري جلب الستوري…", -1)
            if (!downloadInstagramApi(url, nid)) {
                throw IOException("لا توجد ستوريات متاحة حالياً (ربما انتهت)")
            }
            return
        }

        val err: Throwable = try {
            downloadViaYtDlp(url, nid, source)
            return
        } catch (e: Throwable) {
            e
        }

        // بديل 1: واجهة إنستغرام بجلسة المستخدم (بوستات الصور، أو ريلز تتطلب دخول)
        var apiErr: Throwable? = null
        if (isIg && IgSession.isLoggedIn(applicationContext)) {
            progressNotif(nid, "جاري المحاولة بحسابك…", -1)
            try {
                if (downloadInstagramApi(url, nid)) return
            } catch (e: Exception) {
                apiErr = e
            }
        }

        // بديل 2: بدون تسجيل دخول (صور عامة)
        progressNotif(nid, "جاري البحث عن صور…", -1)
        if (!downloadImages(url, nid, source)) throw (apiErr ?: err)
    }

    private fun downloadInstagramApi(url: String, nid: Int): Boolean {
        val res = InstagramApi.resolve(applicationContext, url) ?: return false
        return saveUrls(res.items.map { it.url }, res.label, nid, "Instagram")
    }

    private fun downloadImages(url: String, nid: Int, source: String): Boolean {
        val found = (try {
            ImageFetcher.find(url, source)
        } catch (e: Exception) {
            null
        }) ?: return false
        return saveUrls(found.urls, found.id, nid, source)
    }

    /** ينزّل قائمة روابط (صور/فيديو) ويحفظها في المعرض ويضيفها للسجل. */
    private fun saveUrls(urls: List<String>, label: String, nid: Int, source: String): Boolean {
        if (urls.isEmpty()) return false
        val dir = File(cacheDir, "dl_${System.nanoTime()}").apply { mkdirs() }
        try {
            var saved = 0
            var lastUri: Uri? = null
            for ((i, u) in urls.withIndex()) {
                progressNotif(nid, "جاري التحميل ${i + 1}/${urls.size}…", i * 100 / urls.size)
                val f = (try {
                    ImageFetcher.download(u, dir, i, source)
                } catch (e: Exception) {
                    null
                }) ?: continue
                val title = if (urls.size > 1) "$source $label (${i + 1})" else "$source $label"
                val (uri, kind) = saveMedia(f, title, title, null)
                History.add(
                    this,
                    HistoryItem(System.nanoTime(), title, source, kind, uri.toString(), System.currentTimeMillis())
                )
                lastUri = uri
                saved++
            }
            if (saved == 0) return false
            finalNotif(nid, "تم التحميل ✓", "$saved ملف من $source", lastUri)
            return true
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun downloadViaYtDlp(url: String, nid: Int, source: String) {
        progressNotif(nid, "جاري التحضير…", -1)
        YtDlpHelper.ensureReady(applicationContext)

        val dir = File(cacheDir, "ytdlp_${System.nanoTime()}").apply { mkdirs() }
        try {
            progressNotif(nid, "جاري التحميل من $source…", -1)
            try {
                runYtDlp(url, dir, source)
            } catch (e: YoutubeDLException) {
                // غالباً تغيّر الموقع: نحدّث yt-dlp ونعيد المحاولة مرة وحدة
                progressNotif(nid, "تحديث أداة التحميل…", -1)
                YtDlpHelper.update(applicationContext)
                dir.listFiles()?.forEach { it.deleteRecursively() }
                runYtDlp(url, dir, source)
            }

            val files = dir.listFiles().orEmpty()
                .filter { it.isFile && it.extension.lowercase() in MEDIA_EXT }
                .sortedBy { it.name }
            if (files.isEmpty()) throw IOException("لم يُعثر على ملفات للتحميل")

            var lastUri: Uri? = null
            var firstTitle = ""
            for ((i, f) in files.withIndex()) {
                progressNotif(nid, "جاري الحفظ ${i + 1}/${files.size}…", -1)
                val (title, uploader) = readMeta(dir, f, source)
                if (i == 0) firstTitle = title
                val base = if (files.size > 1) "$title ${i + 1}" else title
                val (uri, kind) = saveMedia(f, base, title, uploader)
                History.add(
                    this,
                    HistoryItem(System.nanoTime(), title, source, kind, uri.toString(), System.currentTimeMillis())
                )
                lastUri = uri
            }
            val text = if (files.size == 1) firstTitle else "${files.size} ملفات من $source"
            finalNotif(nid, "تم التحميل ✓", text, lastUri)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun runYtDlp(url: String, dir: File, source: String) {
        val req = YoutubeDLRequest(url)
        req.addOption("-o", dir.absolutePath + "/%(id)s.%(ext)s")
        req.addOption("--write-info-json")
        req.addOption("--no-mtime")
        if (source == "TikTok") {
            // نستبعد صيغة "download" لأنها بعلامة مائية
            req.addOption("--no-playlist")
            // نفضّل H.264 لأن صيغة bytevc1 (HEVC) لا تشتغل على كثير من المشغّلات
            req.addOption("-f", "best[format_id!=download][format_id!*=bytevc1]/best[format_id!=download]/best")
        } else {
            // إنستغرام: قد يكون الفيديو والصوت منفصلين فندمجهم
            req.addOption("-f", "bv*+ba/b")
            req.addOption("--merge-output-format", "mp4")
            // الستوريات تتطلب جلسة تسجيل دخول (حتى للحسابات العامة)
            IgSession.cookieFile(applicationContext)?.let {
                req.addOption("--cookies", it.absolutePath)
            }
        }
        YoutubeDL.getInstance().execute(req)
    }

    private fun str(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key)

    private fun readMeta(dir: File, f: File, source: String): Pair<String, String> {
        var title = ""
        var uploader = ""
        val jf = File(dir, f.nameWithoutExtension + ".info.json")
        if (jf.exists()) {
            try {
                val o = JSONObject(jf.readText())
                title = str(o, "title").ifBlank { str(o, "description") }
                uploader = str(o, "uploader").ifBlank { str(o, "channel") }
            } catch (e: Exception) {
                // نتجاهل
            }
        }
        title = title.replace(Regex("\\s+"), " ").trim().take(100)
        if (title.isEmpty()) title = "$source ${f.nameWithoutExtension}"
        if (uploader.isEmpty()) uploader = source
        return Pair(title, uploader)
    }

    // ----------------------- الحفظ في المعرض ------------------------

    private class MType(val collection: Uri, val mime: String, val dir: String, val kind: String)

    private fun mediaType(ext: String): MType? {
        val vol = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val video = MediaStore.Video.Media.getContentUri(vol)
        val image = MediaStore.Images.Media.getContentUri(vol)
        val audio = MediaStore.Audio.Media.getContentUri(vol)
        return when (ext) {
            "mp4", "m4v" -> MType(video, "video/mp4", Environment.DIRECTORY_MOVIES, "video")
            "mov" -> MType(video, "video/quicktime", Environment.DIRECTORY_MOVIES, "video")
            "webm" -> MType(video, "video/webm", Environment.DIRECTORY_MOVIES, "video")
            "mkv" -> MType(video, "video/x-matroska", Environment.DIRECTORY_MOVIES, "video")
            "jpg", "jpeg" -> MType(image, "image/jpeg", Environment.DIRECTORY_PICTURES, "image")
            "png" -> MType(image, "image/png", Environment.DIRECTORY_PICTURES, "image")
            "webp" -> MType(image, "image/webp", Environment.DIRECTORY_PICTURES, "image")
            "heic" -> MType(image, "image/heic", Environment.DIRECTORY_PICTURES, "image")
            "m4a" -> MType(audio, "audio/mp4", Environment.DIRECTORY_MUSIC, "audio")
            "mp3" -> MType(audio, "audio/mpeg", Environment.DIRECTORY_MUSIC, "audio")
            else -> null
        }
    }

    /** يحفظ الملف في Music / Movies / Pictures داخل مجلد YtAudio ويرجع (Uri، نوع الملف). */
    private fun saveMedia(file: File, baseName: String, title: String, artist: String?): Pair<Uri, String> {
        val ext = file.extension.lowercase()
        val t = mediaType(ext) ?: throw IOException("صيغة غير مدعومة: $ext")
        val safe = baseName.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(80).ifEmpty { "file" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$safe.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, t.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, t.dir + "/YtAudio")
            put(MediaStore.MediaColumns.TITLE, title)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (t.kind == "audio" && artist != null) {
                put(MediaStore.Audio.AudioColumns.ARTIST, artist)
            }
        }
        val resolver = contentResolver
        val uri = resolver.insert(t.collection, values)
            ?: throw IOException("تعذر إنشاء الملف في المعرض")
        resolver.openOutputStream(uri)?.use { os ->
            file.inputStream().use { it.copyTo(os) }
        } ?: throw IOException("تعذر الكتابة في المعرض")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Pair(uri, t.kind)
    }

    // ------------------------- الأخطاء ------------------------------

    private fun friendly(e: Throwable, url: String = ""): String {
        val msg = e.message ?: return "خطأ غير معروف"
        val low = msg.lowercase()
        val isIg = url.contains("instagram.com") || url.contains("instagr.am")
        val loggedIn = IgSession.isLoggedIn(applicationContext)

        if (msg == "LOGIN") {
            return if (loggedIn) {
                "رفض إنستغرام الجلسة (انتهت أو طُلب تحقق أمني). افتح تطبيق إنستغرام واقبل أي تنبيه أمني، ثم سجّل الخروج والدخول من هنا"
            } else {
                "هذا المحتوى يتطلب تسجيل الدخول. سجّل الدخول من الشاشة الرئيسية"
            }
        }

        if (e is IgRateLimit) return msg

        val line = msg.lines().lastOrNull { it.contains("ERROR", ignoreCase = true) }
            ?: msg.lines().lastOrNull { it.isNotBlank() }
            ?: msg
        val raw = line.replace(Regex("^.*?ERROR:\\s*"), "").trim().take(140)

        if (isIg && low.contains("no video formats")) {
            return if (loggedIn) "تعذر جلب الصور من هذا المنشور ($raw)"
            else "هذا المنشور صور فقط، وتحميلها يحتاج تسجيل الدخول من الشاشة الرئيسية"
        }
        if (isIg && (low.contains("login") || low.contains("logged-in") || low.contains("log in") ||
                low.contains("cookies") || low.contains("empty media response"))
        ) {
            return if (loggedIn) "تعذر الوصول للمحتوى: $raw" else "إنستغرام يطلب تسجيل الدخول لهذا المحتوى. سجّل الدخول من الشاشة الرئيسية"
        }
        if (low.contains("private")) return "المحتوى خاص ولا يمكن تحميله"
        return raw
    }

    // ------------------------- الإشعارات ----------------------------

    private fun progressNotif(nid: Int, title: String, percent: Int) {
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (percent in 0..100) b.setProgress(100, percent, false) else b.setProgress(0, 0, true)
        nm.notify(nid, b.build())
    }

    private fun finalNotif(nid: Int, title: String, text: String, uri: Uri?) {
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(
                if (uri != null) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(false)
            .setAutoCancel(true)
        if (uri != null) {
            val view = Intent(Intent.ACTION_VIEW).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            b.setContentIntent(
                PendingIntent.getActivity(
                    this, nid, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        nm.notify(nid, b.build())
    }
}
