package com.tara.dynamicisland

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader

/**
 * Hosts assets/index.html (Dynamic Island Lab) in a full-screen WebView.
 *
 * - Page is served from https://appassets.androidplatform.net/assets/ so it counts as a
 *   secure context (getUserMedia needs that) instead of file://.
 * - Window draws edge-to-edge, including behind the camera cutout; system-bar / cutout
 *   insets are applied as padding so content never hides under the notch.
 * - The bar area is tinted to match the page background, light or dark.
 */
class LabActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    private var pendingMicRequest: PermissionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge + draw into the display cutout (notch / punch-hole).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            overScrollMode = WebView.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
            }
            webViewClient = AppClient()
            webChromeClient = AppChromeClient()
        }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true) // chrome://inspect on debug builds
        }

        root = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.page_bg))
            addView(web)
        }
        setContentView(root)

        // Keep content clear of the status bar, cutout and gesture/nav bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        if (savedInstanceState == null) {
            web.loadUrl(START_URL)
        } else {
            web.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Theme (light/dark) may have flipped; let the page react, then re-tint the bars.
        web.postDelayed({ syncBarsToPage() }, 200)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    /** Paints the area behind the status bar / notch with the page's own background colour. */
    private fun syncBarsToPage() {
        web.evaluateJavascript("getComputedStyle(document.body).backgroundColor") { raw ->
            val m = Regex("(\\d+)[,\\s]+(\\d+)[,\\s]+(\\d+)").find(raw ?: return@evaluateJavascript)
                ?: return@evaluateJavascript
            val (r, g, b) = m.destructured
            val color = Color.rgb(r.toInt(), g.toInt(), b.toInt())
            root.setBackgroundColor(color)
            val lightBg = ColorUtils.calculateLuminance(color) > 0.5
            WindowInsetsControllerCompat(window, root).apply {
                isAppearanceLightStatusBars = lightBg
                isAppearanceLightNavigationBars = lightBg
            }
        }
    }

    // ---- microphone permission bridge (page's getUserMedia -> Android runtime permission) ----

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        val req = pendingMicRequest ?: return
        pendingMicRequest = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            req.deny()
        }
    }

    private inner class AppChromeClient : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                val fromOurPage = request.origin.toString().startsWith(APP_ORIGIN)
                val wantsMicOnly =
                    request.resources.all { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                if (!fromOurPage || !wantsMicOnly) {
                    request.deny()
                    return@runOnUiThread
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    pendingMicRequest?.deny()
                    pendingMicRequest = request
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                }
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingMicRequest == request) pendingMicRequest = null
        }
    }

    private inner class AppClient : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView, request: WebResourceRequest
        ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.url.toString().startsWith(APP_ORIGIN)) return false
            // Anything external opens in the user's browser instead of inside the lab.
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            syncBarsToPage()
        }
    }

    companion object {
        private const val APP_ORIGIN = "https://appassets.androidplatform.net"
        private const val START_URL = "$APP_ORIGIN/assets/index.html"
        private const val REQ_MIC = 42
    }
}
