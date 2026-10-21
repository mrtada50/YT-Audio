package com.ytaudio.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat

/** شاشة شفافة تستقبل الرابط المشارك، تبدأ التحميل بالخلفية وتُغلق فوراً. */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = Links.find(text)

        if (url == null) {
            Toast.makeText(this, "الرابط غير مدعوم (يوتيوب / تيك توك / إنستغرام)", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        ContextCompat.startForegroundService(
            this,
            Intent(this, DownloadService::class.java).putExtra(DownloadService.EXTRA_URL, url)
        )
        Toast.makeText(this, "بدأ التحميل بالخلفية ⬇️", Toast.LENGTH_SHORT).show()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }
}
