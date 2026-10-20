package com.autoshorts.bot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var statusView: TextView
    private val h = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val on = BotService.instance != null
            statusView.text = (if (on) "" else "⚠ Accessibility service is OFF\n") + BotService.status
            h.postDelayed(this, 1000)
        }
    }

    private fun prefs() = getSharedPreferences("cfg", Context.MODE_PRIVATE)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        val sv = ScrollView(this).apply { addView(ll) }
        setContentView(sv)
        fun title(t: String) = ll.addView(TextView(this).apply { text = t; textSize = 22f })
        fun field(key: String, label: String, def: String, number: Boolean = false) {
            ll.addView(TextView(this).apply { text = label; setPadding(0, 24, 0, 0) })
            val e = EditText(this).apply {
                setText(if (number) prefs().getInt(key, def.toInt()).toString() else prefs().getString(key, def))
                if (number) inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine()
            }
            fields[key] = e; ll.addView(e)
        }
        title("Shorts Bot")
        field("base", "AI base URL", "http://127.0.0.1:10100/v1")
        field("model", "AI model", "gpt-luna-6")
        field("key", "API key", "ocx_data_941d0a6e2035ea8e6f78dd940fc4baafba3911ad")
        field("likeP", "Like chance %", "70", true)
        field("saveP", "Save chance %", "30", true)
        field("commentP", "Comment chance %", "40", true)
        field("watchMin", "Watch min seconds", "6", true)
        field("watchMax", "Watch max seconds", "14", true)
        field("maxVideos", "Stop after N videos", "50", true)

        fun btn(t: String, f: () -> Unit) = ll.addView(Button(this).apply { text = t; setOnClickListener { f() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        btn("1. Enable accessibility service") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btn("2. Save & START (opens YouTube)") {
            save()
            val svc = BotService.instance
            if (svc == null) toast("Enable the Shorts Bot accessibility service first")
            else {
                svc.start()
                packageManager.getLaunchIntentForPackage("com.google.android.youtube")?.let { startActivity(it) }
                toast("Open a Short; the bot works once a Short is on screen")
            }
        }
        btn("Show floating popup") { BotService.instance?.showOverlay() ?: toast("Enable the accessibility service first") }
        btn("STOP") { BotService.instance?.stop() }
        statusView = TextView(this).apply { setPadding(0, 30, 0, 0) }
        ll.addView(statusView)
    }

    private fun save() {
        val e = prefs().edit()
        for ((k, v) in fields) {
            val s = v.text.toString().trim()
            if (k in listOf("base", "model", "key")) e.putString(k, s) else e.putInt(k, s.toIntOrNull() ?: 0)
        }
        e.apply()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    override fun onResume() { super.onResume(); h.post(ticker) }
    override fun onPause() { super.onPause(); h.removeCallbacks(ticker) }
}
