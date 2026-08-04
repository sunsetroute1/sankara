package com.joshuatz.nfceinkwriter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import com.google.android.material.appbar.MaterialToolbar
import com.joshuatz.nfceinkwriter.trailtag.TrailTagQr

/**
 * In-app preview of the universal safety profile page (same HTML as public viewer).
 * Also handles sankara://trailtag deep links on the same device.
 */
class TrailTagLocalProfileActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trail_tag_local_profile)
        SystemBarUtils.applyStatusBarInset(findViewById(R.id.trailTagLocalAppBar))

        val token = intent.getStringExtra(EXTRA_QR_TOKEN)
            ?: intent.data?.getQueryParameter("d")

        findViewById<MaterialToolbar>(R.id.trail_tag_local_toolbar).setNavigationOnClickListener { finish() }

        val webView = findViewById<WebView>(R.id.trailTagLocalWebView)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/trailtag/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): android.webkit.WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (url.startsWith("tel:")) {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
                    return true
                }
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    return true
                }
                return false
            }
        }

        if (token.isNullOrBlank()) {
            Toast.makeText(this, R.string.trail_tag_local_profile_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        webView.loadUrl("https://appassets.androidplatform.net/trailtag/index.html?d=${Uri.encode(token)}")
    }

    companion object {
        const val EXTRA_QR_TOKEN = "trail_tag_qr_token"
    }
}
