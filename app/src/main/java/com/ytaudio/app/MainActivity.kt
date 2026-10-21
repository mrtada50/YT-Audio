package com.ytaudio.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private val items = ArrayList<HistoryItem>()
    private val io = Executors.newFixedThreadPool(2)
    private val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    private val thumbs = LruCache<Long, Bitmap>(60)
    private lateinit var adapter: ItemAdapter
    private lateinit var igBtn: Button

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = TextView(this).apply {
            text = "YT Audio\nشارك رابط من يوتيوب (صوت M4A) أو تيك توك (فيديو بدون علامة مائية وصور) أو إنستغرام (فيديو وصور وستوري)، أو انسخ الرابط واضغط «لصق رابط وتحميل»."
            textSize = 14f
            setPadding(dp(16), dp(20), dp(16), dp(12))
        }

        val list = ListView(this)
        val empty = TextView(this).apply {
            text = "لا توجد تحميلات بعد"
            gravity = Gravity.CENTER
            textSize = 16f
        }
        val frame = FrameLayout(this)
        frame.addView(list, FrameLayout.LayoutParams(match, match))
        frame.addView(empty, FrameLayout.LayoutParams(match, match))
        list.emptyView = empty

        val pasteBtn = Button(this).apply {
            text = "لصق رابط وتحميل"
            setOnClickListener { pasteAndDownload() }
        }
        igBtn = Button(this).apply { setOnClickListener { onIgClick() } }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        btnRow.addView(pasteBtn, LinearLayout.LayoutParams(0, wrap, 1f))
        btnRow.addView(igBtn, LinearLayout.LayoutParams(0, wrap, 1f))

        val updateBtn = Button(this).apply {
            text = "تحديث التطبيق (v${Updater.currentName(this@MainActivity)})"
            setOnClickListener { checkUpdate(true) }
        }

        root.addView(header, LinearLayout.LayoutParams(match, wrap))
        root.addView(btnRow, LinearLayout.LayoutParams(match, wrap))
        root.addView(
            updateBtn,
            LinearLayout.LayoutParams(match, wrap).apply { setMargins(dp(8), 0, dp(8), dp(8)) }
        )
        root.addView(frame, LinearLayout.LayoutParams(match, 0, 1f))
        setContentView(root)

        adapter = ItemAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> openItem(items[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            showMenu(items[pos])
            true
        }

        // اختصار المشاركة المباشرة: يرفع التطبيق لأعلى قائمة المشاركة
        val shortcut = ShortcutInfoCompat.Builder(this, "yt_audio_share")
            .setShortLabel("YT Audio")
            .setLongLived(true)
            .setIntent(Intent(this, ShareActivity::class.java).setAction(Intent.ACTION_SEND))
            .setCategories(setOf("com.ytaudio.app.category.SHARE_TARGET"))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_launcher))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        Updater.cleanupIfInstalled(this)
        scheduleUpdateChecks()
        handleIntent(intent)

        // فحص تلقائي للتحديث مرة كل 12 ساعة
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("last_update_check", 0L) > 12L * 3600 * 1000) {
            prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()
            checkUpdate(false)
        }
    }

    private fun checkUpdate(manual: Boolean) {
        if (manual) Toast.makeText(this, "جاري فحص التحديث…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val info = Updater.check(this)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (info == null) {
                        if (manual) Toast.makeText(this, "أنت على آخر إصدار ✓", Toast.LENGTH_SHORT).show()
                    } else {
                        showUpdateDialog(info)
                    }
                }
            } catch (e: Exception) {
                if (manual) {
                    runOnUiThread {
                        Toast.makeText(this, "تعذر فحص التحديث: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun scheduleUpdateChecks() {
        val req = PeriodicWorkRequest.Builder(UpdateCheckWorker::class.java, 12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("update_check", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("install_update", false)) {
            i.removeExtra("install_update")
            Updater.installReady(this)
        } else if (i.getBooleanExtra("show_update", false)) {
            i.removeExtra("show_update")
            checkUpdate(false)
        }
    }

    private fun showUpdateDialog(info: Updater.Info) {
        if (Updater.isReady(this, info.code)) {
            AlertDialog.Builder(this)
                .setTitle("التحديث جاهز (إصدار ${info.code})")
                .setMessage("تم تنزيله بالفعل. اضغط تثبيت ليُثبَّت فوق النسخة الحالية.")
                .setPositiveButton("تثبيت الآن") { _, _ -> Updater.installReady(this) }
                .setNegativeButton("لاحقاً", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("تحديث متوفر (إصدار ${info.code})")
            .setMessage(info.notes.take(400).ifEmpty { "نسخة جديدة من التطبيق." })
            .setPositiveButton("تنزيل بالخلفية") { _, _ -> startUpdate(info) }
            .setNegativeButton("لاحقاً", null)
            .show()
    }

    private fun startUpdate(info: Updater.Info) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, UpdateService::class.java)
                .putExtra(UpdateService.EXTRA_URL, info.apkUrl)
                .putExtra(UpdateService.EXTRA_CODE, info.code)
        )
        Toast.makeText(
            this, "بدأ تنزيل التحديث بالخلفية، سيصلك إشعار عند اكتماله", Toast.LENGTH_LONG
        ).show()
    }

    override fun onResume() {
        super.onResume()
        History.listener = { refresh() }
        updateIgButton()
        refresh()
    }

    private fun updateIgButton() {
        igBtn.text = if (IgSession.isLoggedIn(this)) "إنستغرام: تسجيل الخروج" else "إنستغرام: تسجيل الدخول"
    }

    private fun onIgClick() {
        if (IgSession.isLoggedIn(this)) {
            AlertDialog.Builder(this)
                .setMessage("تسجيل الخروج من إنستغرام داخل التطبيق؟")
                .setPositiveButton("نعم") { _, _ ->
                    IgSession.clear(this)
                    updateIgButton()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("تسجيل دخول إنستغرام")
                .setMessage(
                    "مطلوب فقط لتحميل الستوريات (إنستغرام يشترط تسجيل الدخول لها حتى للحسابات العامة). " +
                        "تُحفظ الجلسة داخل التطبيق فقط. قد يقيّد إنستغرام الحسابات التي تحمّل كثيراً، لذلك يُفضّل استخدام حساب ثانوي."
                )
                .setPositiveButton("متابعة") { _, _ ->
                    startActivity(Intent(this, LoginActivity::class.java))
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }

    private fun pasteAndDownload() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        } else ""
        val url = Links.find(text)
        if (url == null) {
            Toast.makeText(this, "لا يوجد رابط مدعوم في الحافظة", Toast.LENGTH_SHORT).show()
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, DownloadService::class.java).putExtra(DownloadService.EXTRA_URL, url)
        )
        Toast.makeText(this, "بدأ التحميل بالخلفية ⬇️", Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        History.listener = null
        super.onPause()
    }

    private fun refresh() {
        io.execute {
            val l = History.load(this)
            runOnUiThread {
                items.clear()
                items.addAll(l)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun mimeOf(kind: String) = when (kind) {
        "audio" -> "audio/*"
        "video" -> "video/*"
        else -> "image/*"
    }

    private fun kindLabel(kind: String) = when (kind) {
        "audio" -> "صوت"
        "video" -> "فيديو"
        else -> "صورة"
    }

    private fun openItem(item: HistoryItem) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(item.uri), mimeOf(item.kind))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح الملف (ربما تم حذفه)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareItem(item: HistoryItem) {
        try {
            val send = Intent(Intent.ACTION_SEND)
                .setType(mimeOf(item.kind))
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "مشاركة"))
        } catch (e: Exception) {
            Toast.makeText(this, "تعذرت المشاركة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteMedia(item: HistoryItem) {
        io.execute {
            try {
                contentResolver.delete(Uri.parse(item.uri), null, null)
            } catch (e: Exception) {
                // قد يكون محذوفاً مسبقاً
            }
            History.remove(this, item.id)
        }
    }

    private fun showMenu(item: HistoryItem) {
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(arrayOf("فتح", "مشاركة", "إزالة من القائمة", "حذف الملف من الجهاز")) { _, which ->
                when (which) {
                    0 -> openItem(item)
                    1 -> shareItem(item)
                    2 -> io.execute { History.remove(this, item.id) }
                    3 -> deleteMedia(item)
                }
            }
            .show()
    }

    private inner class ItemAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = items[position].id

        private fun buildRow(): LinearLayout {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            val img = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            row.addView(img, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(12) })

            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(this@MainActivity).apply {
                textSize = 16f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val sub = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(0xFF777777.toInt())
            }
            col.addView(title)
            col.addView(sub)
            row.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))
            return row
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: buildRow()
            val item = items[position]
            val img = row.getChildAt(0) as ImageView
            val col = row.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).text = item.title
            (col.getChildAt(1) as TextView).text =
                "${item.source} • ${kindLabel(item.kind)} • ${fmt.format(Date(item.time))}"

            img.tag = item.id
            val cached = thumbs.get(item.id)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                img.setImageResource(
                    if (item.kind == "image") android.R.drawable.ic_menu_gallery
                    else android.R.drawable.ic_media_play
                )
                io.execute {
                    val bmp = try {
                        contentResolver.loadThumbnail(Uri.parse(item.uri), Size(160, 160), null)
                    } catch (e: Exception) {
                        null
                    }
                    if (bmp != null) {
                        thumbs.put(item.id, bmp)
                        runOnUiThread { if (img.tag == item.id) img.setImageBitmap(bmp) }
                    }
                }
            }
            return row
        }
    }
}
