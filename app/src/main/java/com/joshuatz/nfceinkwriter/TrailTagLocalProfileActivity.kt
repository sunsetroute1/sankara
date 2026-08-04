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
import com.joshuatz.nfceinkwriter.trailtag.TrailTagHtmlGenerator
import com.joshuatz.nfceinkwriter.trailtag.TrailTagRepository
import java.io.File

/**
 * Offline preview of the locally generated safety profile HTML.
 * Handles tel:, sms:, and https: tracking links.
 */
class TrailTagLocalProfileActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trail_tag_local_profile)
        SystemBarUtils.applyStatusBarInset(findViewById(R.id.trailTagLocalAppBar))

        findViewById<MaterialToolbar>(R.id.trail_tag_local_toolbar).setNavigationOnClickListener { finish() }

        val htmlFile = intent.getStringExtra(EXTRA_HTML_PATH)?.let { File(it) }
            ?: TrailTagRepository(this).localHtmlIndexFile()

        if (!htmlFile.exists()) {
            Toast.makeText(this, R.string.trail_tag_local_profile_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val webView = findViewById<WebView>(R.id.trailTagLocalWebView)
        val bundleDir = TrailTagHtmlGenerator.bundleDir(this)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/trailtag/", WebViewAssetLoader.InternalStoragePathHandler(this, bundleDir))
            .build()

        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): android.webkit.WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleExternalLink(url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleExternalLink(request.url.toString())
        }

        webView.loadUrl("https://appassets.androidplatform.net/trailtag/index.html")
    }

    private fun handleExternalLink(url: String): Boolean {
        when {
            url.startsWith("tel:") -> {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
                return true
            }
            url.startsWith("sms:") || url.startsWith("smsto:") -> {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(url)))
                return true
            }
            url.startsWith("http://") || url.startsWith("https://") -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return true
            }
        }
        return false
    }

    companion object {
        const val EXTRA_HTML_PATH = "trail_tag_html_path"
        /** @deprecated Token-based viewer — use [EXTRA_HTML_PATH] with local HTML. */
        const val EXTRA_QR_TOKEN = "trail_tag_qr_token"
    }
}
