package com.ytaudio.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/** يفتح صفحة تسجيل دخول إنستغرام، ويحفظ الجلسة محلياً داخل التطبيق فقط. */
class LoginActivity : Activity() {

    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        setContentView(web)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        CookieManager.getInstance().setAcceptCookie(true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                checkLogin()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                checkLogin()
            }
        }
        web.loadUrl("https://www.instagram.com/accounts/login/")
    }

    private fun checkLogin() {
        if (done) return
        val c = CookieManager.getInstance().getCookie("https://www.instagram.com") ?: return
        if (c.contains("sessionid=")) {
            done = true
            CookieManager.getInstance().flush()
            IgSession.saveFromCookieString(this, c)
            Toast.makeText(this, "تم تسجيل الدخول ✓", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
