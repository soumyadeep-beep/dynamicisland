package com.tara.dynamicisland

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import kotlin.math.abs
import kotlin.math.max

/**
 * Draws the Dynamic Island on top of every app, at the camera cutout.
 *
 * A transparent WebView runs the same index.html (in ?overlay=1 mode) inside a
 * TYPE_APPLICATION_OVERLAY window. The page reports its target size through
 * AndroidIsland.target(w, h); the window is resized to match, so the area around the
 * island stays touchable by the app underneath.
 *
 * Real events handled here: charger connected, battery low, ringer mode, airplane mode.
 */
class IslandService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var lp: LP
    private val main = Handler(Looper.getMainLooper())

    private var container: FrameLayout? = null
    private var web: WebView? = null
    private var curW = IDLE_W
    private var curH = IDLE_H
    private var shrinkTask: Runnable? = null
    private var pageReady = false
    private val pendingJs = ArrayList<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        addOverlay()
        registerSystemReceivers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_RELAYOUT -> {
                applyParams()
                updateVisibility(isIdle(curW, curH))
            }
            ACTION_SHOW -> intent.getStringExtra(EXTRA_KEY)?.let {
                show(it, intent.getIntExtra(EXTRA_MS, 4000), -1)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(receiver) }
        shrinkTask?.let { main.removeCallbacks(it) }
        container?.let { runCatching { wm.removeView(it) } }
        web?.destroy()
        container = null
        web = null
        super.onDestroy()
    }

    // ---- foreground notification ---------------------------------------------------------

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Island service", NotificationManager.IMPORTANCE_MIN)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, IslandService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle("Dynamic Island is running")
            .setContentText("Tap to adjust position, or stop it here")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    // ---- overlay window ------------------------------------------------------------------

    private fun baseFlags() =
        LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCH_MODAL or
            LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS

    private fun addOverlay() {
        val d = resources.displayMetrics.density
        lp = LP(
            ((curW + PAD * 2) * d).toInt(),
            ((curH + PAD) * d).toInt(),
            LP.TYPE_APPLICATION_OVERLAY,
            baseFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            title = "DynamicIsland"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = LP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = LP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        val w = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(Bridge(), "AndroidIsland")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest
                ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String) {
                    pageReady = true
                    pendingJs.forEach { view.evaluateJavascript(it, null) }
                    pendingJs.clear()
                }
            }
        }
        web = w

        val c = FrameLayout(this)
        c.addView(w, FrameLayout.LayoutParams(-1, -1))
        container = c
        applyParams()
        wm.addView(c, lp)
        updateVisibility(true)
        w.loadUrl(PAGE_URL)
    }

    /** Window = island target size + a little padding so the spring overshoot isn't clipped. */
    private fun applyParams() {
        val d = resources.displayMetrics.density
        lp.width = ((curW + PAD * 2) * d).toInt()
        lp.height = ((curH + PAD) * d).toInt()
        lp.x = (prefs.getInt(PREF_X, 0) * d).toInt()
        lp.y = (prefs.getInt(PREF_Y, 4) * d).toInt()
        pushLayout()
    }

    private fun pushLayout() {
        container?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    private fun isIdle(w: Double, h: Double) = abs(w - IDLE_W) < 0.5 && abs(h - IDLE_H) < 0.5

    /** When idle (and "always show" is off) the window is invisible and lets touches through. */
    private fun updateVisibility(idle: Boolean) {
        val hide = idle && !prefs.getBoolean(PREF_ALWAYS, false)
        container?.visibility = if (hide) View.INVISIBLE else View.VISIBLE
        lp.flags = if (hide) baseFlags() or LP.FLAG_NOT_TOUCHABLE else baseFlags()
        pushLayout()
    }

    private fun onTarget(w: Double, h: Double) {
        shrinkTask?.let { main.removeCallbacks(it) }
        val idle = isIdle(w, h)
        if (!idle) updateVisibility(false)
        // Grow right away; shrink after the collapse animation has played.
        curW = max(curW, w)
        curH = max(curH, h)
        applyParams()
        if (curW != w || curH != h) {
            val t = Runnable {
                curW = w
                curH = h
                applyParams()
                updateVisibility(idle)
            }
            shrinkTask = t
            main.postDelayed(t, 450)
        } else {
            updateVisibility(idle)
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun target(w: Double, h: Double) {
            main.post { onTarget(w, h) }
        }
    }

    // ---- driving the page ----------------------------------------------------------------

    private fun runJs(js: String) {
        main.post {
            val w = web ?: return@post
            if (pageReady) w.evaluateJavascript(js, null) else pendingJs.add(js)
        }
    }

    private fun show(key: String, ms: Int, pct: Int) {
        val safe = key.filter { it.isLetterOrDigit() }
        val p = if (pct >= 0) pct.toString() else "null"
        runJs("window.__api&&window.__api.show('$safe',$ms,$p)")
    }

    // ---- real phone events ---------------------------------------------------------------

    private fun batteryPct(): Int {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0) -1 else level * 100 / scale
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> show("charge", 3000, batteryPct())
                Intent.ACTION_BATTERY_LOW -> show("lowbat", 4000, batteryPct())
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    val am = c.getSystemService(AUDIO_SERVICE) as AudioManager
                    val key = when (am.ringerMode) {
                        AudioManager.RINGER_MODE_SILENT -> "rgSilent"
                        AudioManager.RINGER_MODE_VIBRATE -> "rgVibrate"
                        else -> "rgRing"
                    }
                    show(key, 2400, -1)
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED ->
                    if (i.getBooleanExtra("state", false)) show("rgAir", 2400, -1)
            }
        }
    }

    private fun registerSystemReceivers() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_EXPORTED)
    }

    companion object {
        const val PREFS = "island"
        const val PREF_X = "x"
        const val PREF_Y = "y"
        const val PREF_ALWAYS = "always"

        const val ACTION_STOP = "com.tara.dynamicisland.STOP"
        const val ACTION_RELAYOUT = "com.tara.dynamicisland.RELAYOUT"
        const val ACTION_SHOW = "com.tara.dynamicisland.SHOW"
        const val EXTRA_KEY = "key"
        const val EXTRA_MS = "ms"

        @Volatile
        var running = false

        private const val CHANNEL = "island_service"
        private const val NOTIF_ID = 7
        private const val IDLE_W = 126.0
        private const val IDLE_H = 37.0
        private const val PAD = 12.0
        private const val PAGE_URL =
            "https://appassets.androidplatform.net/assets/index.html?overlay=1"
    }
}
