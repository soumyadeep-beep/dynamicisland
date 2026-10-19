package com.tara.dynamicisland

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/** Setup screen: grant overlay permission, start/stop the island, line it up, test it. */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var permBtn: Button
    private lateinit var runBtn: Button
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(IslandService.PREFS, MODE_PRIVATE)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }

        fun label(t: String, size: Float = 15f, color: Int = Color.WHITE, bold: Boolean = false) =
            TextView(this).apply {
                text = t
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(4))
            }

        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        fun slider(title: String, key: String, lo: Int, hi: Int, def: Int) {
            val cur = prefs.getInt(key, def)
            val v = label("$title: $cur dp")
            root.addView(v)
            root.addView(SeekBar(this).apply {
                this.max = hi - lo
                progress = cur - lo
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        val value = p + lo
                        v.text = "$title: $value dp"
                        prefs.edit().putInt(key, value).apply()
                        if (fromUser) relayout()
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            })
        }

        root.addView(label("Dynamic Island", 26f, bold = true))
        status = label("", 15f, Color.parseColor("#8E8E93"))
        root.addView(status)

        permBtn = button("1. Allow \"Display over other apps\"") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        root.addView(permBtn)

        runBtn = button("Start island") { toggleService() }
        root.addView(runBtn)

        root.addView(label("Line it up with your camera", 17f, bold = true))
        root.addView(label("Start the island, turn on \"Always show black pill\", then drag until it sits over the camera hole.", 13f, Color.parseColor("#8E8E93")))
        slider("Down from top", IslandService.PREF_Y, 0, 80, 4)
        slider("Sideways (left - / right +)", IslandService.PREF_X, -150, 150, 0)
        root.addView(CheckBox(this).apply {
            text = "Always show black pill"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean(IslandService.PREF_ALWAYS, false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(IslandService.PREF_ALWAYS, on).apply()
                relayout()
            }
        })

        root.addView(label("Test it", 17f, bold = true))
        val tests = listOf(
            "Charging" to "charge", "Low battery" to "lowbat",
            "Silent" to "rgSilent", "Ring" to "rgRing",
            "Music" to "music", "In call" to "callOn",
            "Timer" to "timer", "Navigation" to "nav",
            "Delivery" to "food", "Live score" to "sport"
        )
        tests.chunked(2).forEach { pair ->
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                pair.forEach { (name, key) ->
                    addView(
                        button(name) { testState(key) },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    )
                }
            })
        }

        root.addView(label("Real events it reacts to on its own: charger plugged in, battery low, ringer mode (ring / vibrate / silent) and airplane mode.", 13f, Color.parseColor("#8E8E93")))
        root.addView(button("Open the original lab (preview screen)") {
            startActivity(Intent(this, LabActivity::class.java))
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            isFillViewport = true
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val can = Settings.canDrawOverlays(this)
        val on = IslandService.running
        status.text = when {
            !can -> "Step 1: allow \"Display over other apps\" so the island can sit over your camera."
            !on -> "Permission granted. Tap Start island."
            else -> "Running. It now sits over your camera area in every app."
        }
        permBtn.visibility = if (can) View.GONE else View.VISIBLE
        runBtn.isEnabled = can
        runBtn.text = if (on) "Stop island" else "Start island"
    }

    private fun toggleService() {
        if (IslandService.running) {
            startService(Intent(this, IslandService::class.java).setAction(IslandService.ACTION_STOP))
        } else {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            ContextCompat.startForegroundService(this, Intent(this, IslandService::class.java))
        }
        ui.postDelayed({ refresh() }, 600)
    }

    private fun relayout() {
        if (IslandService.running) {
            startService(Intent(this, IslandService::class.java).setAction(IslandService.ACTION_RELAYOUT))
        }
    }

    private fun testState(key: String) {
        if (!IslandService.running) {
            Toast.makeText(this, "Start the island first", Toast.LENGTH_SHORT).show()
            return
        }
        startService(
            Intent(this, IslandService::class.java)
                .setAction(IslandService.ACTION_SHOW)
                .putExtra(IslandService.EXTRA_KEY, key)
                .putExtra(IslandService.EXTRA_MS, 4000)
        )
    }
}
