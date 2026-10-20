package com.autoshorts.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class BotService : AccessibilityService() {

    companion object {
        @Volatile var instance: BotService? = null
        @Volatile var status: String = "Idle"
        @Volatile var running = false
        private val UI_WORDS = Regex(
            "(?i)^(shorts|subscriptions|live|lens|home|create|you|like|dislike|comments?|share|remix|save|saved|" +
                "subscribe|subscribed|more|search|back|sound|\\d+([.,]\\d+)?[kmb]?)$"
        )
    }

    private var worker: Thread? = null

    override fun onServiceConnected() { instance = this; status = "Service ready"; showOverlay() }

    // ---------- floating popup ----------
    private var overlay: LinearLayout? = null
    private val ui = Handler(Looper.getMainLooper())

    fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 300 }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.argb(210, 20, 20, 20)); setPadding(16, 12, 16, 12) }
        val st = TextView(this).apply { setTextColor(Color.WHITE); textSize = 11f; maxWidth = 520; text = "Shorts Bot  (drag me)" }
        val toggle = Button(this).apply { text = "START"; textSize = 13f }
        val hide = Button(this).apply { text = "Hide"; textSize = 11f }
        toggle.setOnClickListener { if (running) stop() else start() }
        hide.setOnClickListener { hideOverlay() }
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0
        st.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y }
                MotionEvent.ACTION_MOVE -> { lp.x = sx + (e.rawX - dx).toInt(); lp.y = sy + (e.rawY - dy).toInt(); wm.updateViewLayout(box, lp) }
            }
            true
        }
        box.addView(st); box.addView(toggle); box.addView(hide)
        wm.addView(box, lp)
        overlay = box
        val tick = object : Runnable {
            override fun run() {
                if (overlay == null) return
                st.text = "Shorts Bot: $status"
                toggle.text = if (running) "STOP" else "START"
                ui.postDelayed(this, 500)
            }
        }
        ui.post(tick)
    }

    fun hideOverlay() {
        overlay?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (e: Exception) {} }
        overlay = null
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { running = false; hideOverlay(); instance = null; super.onDestroy() }

    // ---------- control ----------
    fun start() {
        if (running) return
        running = true
        worker = Thread { try { loop() } catch (e: InterruptedException) {} catch (e: Exception) { status = "Error: ${e.message}" }; running = false }
        worker!!.start()
    }

    fun stop() { running = false; worker?.interrupt(); status = "Stopped" }

    // ---------- main loop ----------
    private fun loop() {
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val watchMin = p.getInt("watchMin", 6); val watchMax = maxOf(p.getInt("watchMax", 14), watchMin + 1)
        val likeP = p.getInt("likeP", 70); val saveP = p.getInt("saveP", 30); val commentP = p.getInt("commentP", 40)
        val maxVideos = p.getInt("maxVideos", 50)
        var count = 0
        while (running && count < maxVideos) {
            if (rootInActiveWindow?.packageName != "com.google.android.youtube") {
                status = "Waiting for YouTube Shorts to be open..."; sleepMs(2000); continue
            }
            status = "Video ${count + 1}/$maxVideos: watching"
            sleepMs(Random.nextLong(watchMin * 1000L, watchMax * 1000L))
            val caption = readCaption()
            if (chance(likeP)) { status = "Video ${count + 1}: like"; likeVideo(); sleepMs(rnd(600, 1500)) }
            if (chance(saveP)) { status = "Video ${count + 1}: save"; saveVideo(); sleepMs(rnd(600, 1500)) }
            if (chance(commentP)) { status = "Video ${count + 1}: comment"; commentVideo(caption); sleepMs(rnd(500, 1200)) }
            status = "Video ${count + 1}: scrolling"
            swipeNext()
            count++
            sleepMs(rnd(1200, 2200))
        }
        status = if (count >= maxVideos) "Done ($count videos)" else "Stopped"
    }

    // ---------- actions ----------
    private fun likeVideo() {
        val r = rootInActiveWindow ?: return
        val n = find(r) { l, _ -> l.startsWith("like this video") || l == "like" && true }
            .firstOrNull { !label(it).startsWith("dislike") } ?: return
        if (n.isSelected || label(n).contains("liked")) return
        clickNode(n)
    }

    private fun saveVideo() {
        val r = rootInActiveWindow ?: return
        val n = find(r) { l, _ -> l == "save" || l == "save to playlist" }.firstOrNull() ?: return
        clickNode(n) // "Saved" toast ("Saved to Shorts") appears in YouTube
    }

    private fun commentVideo(caption: String) {
        val text = generateComment(caption) ?: run { status = "AI comment failed"; return }
        val r = rootInActiveWindow ?: return
        val open = find(r) { l, _ -> (l.contains("comment") && !l.contains("add a comment")) }
            .firstOrNull { it.isClickable || it.parent?.isClickable == true } ?: return
        clickNode(open)
        sleepMs(rnd(1800, 2600))

        var box = findBox()
        if (box == null) { sleepMs(1500); box = findBox() }
        if (box == null) { closeComments(); return }
        clickNode(box); sleepMs(rnd(500, 900))
        box = findBox() ?: box
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        sleepMs(rnd(1200, 2200)) // "typing" pause

        val send = rootInActiveWindow?.let { rr ->
            find(rr) { l, _ -> l == "send" || l == "comment" || l == "post" || l.contains("send comment") }
                .firstOrNull { it.isClickable || it.parent?.isClickable == true }
        }
        if (send != null) clickNode(send) else tapRelative(0.93f, 0.745f) // fallback: send arrow
        sleepMs(rnd(1500, 2500))
        closeComments()
    }

    private fun findBox(): AccessibilityNodeInfo? {
        val r = rootInActiveWindow ?: return null
        return find(r) { l, n -> l.contains("add a comment") || n.className == "android.widget.EditText" }.firstOrNull()
    }

    private fun closeComments() {
        repeat(3) {
            val r = rootInActiveWindow
            val stillOpen = r != null && (findBox() != null || find(r) { l, _ -> l == "comments" }.isNotEmpty())
            if (!stillOpen) return
            performGlobalAction(GLOBAL_ACTION_BACK)
            sleepMs(rnd(600, 1000))
        }
    }

    private fun swipeNext() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val x1 = w * (0.45f + Random.nextFloat() * 0.1f); val y1 = h * (0.72f + Random.nextFloat() * 0.06f)
        val x2 = x1 + (Random.nextFloat() - 0.5f) * w * 0.08f; val y2 = h * (0.25f + Random.nextFloat() * 0.06f)
        val path = Path().apply { moveTo(x1, y1); quadTo((x1 + x2) / 2 + w * 0.03f, (y1 + y2) / 2, x2, y2) }
        gesture(path, rnd(220, 420))
    }

    private fun tapRelative(fx: Float, fy: Float) {
        val dm = resources.displayMetrics
        tap(dm.widthPixels * fx, dm.heightPixels * fy)
    }

    // ---------- AI ----------
    private fun generateComment(caption: String): String? {
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val base = p.getString("base", "http://127.0.0.1:10100/v1")!!.trimEnd('/')
        val model = p.getString("model", "gpt-luna-6")!!
        val key = p.getString("key", "ocx_data_941d0a6e2035ea8e6f78dd940fc4baafba3911ad")!!
        val sys = "You write one short YouTube Shorts comment as a real viewer. Natural, casual, specific to the video. " +
            "Max 14 words. At most one emoji. No hashtags, no quotes, no mention of being an AI. " +
            "Reply in the same language as the caption. Output only the comment."
        val body = JSONObject().put("model", model).put("max_tokens", 60).put("temperature", 0.9)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", sys))
                .put(JSONObject().put("role", "user").put("content", "Video caption/title: " + caption.ifBlank { "(unknown)" })))
        return try {
            val c = URL("$base/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 45000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (key.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $key")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val resp = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
            if (c.responseCode !in 200..299) { status = "AI HTTP ${c.responseCode}"; return null }
            JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                .trim().trim('"', '\'', ' ', '\n').take(200).ifBlank { null }
        } catch (e: Exception) { status = "AI error: ${e.message}"; null }
    }

    // ---------- reading the screen ----------
    private fun readCaption(): String {
        val r = rootInActiveWindow ?: return ""
        val out = LinkedHashSet<String>()
        walk(r) { n ->
            val raw = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
            if (raw.length >= 8 && !UI_WORDS.matches(raw) && !raw.lowercase().contains("add a comment")
                && !raw.lowercase().startsWith("like this video") && !raw.lowercase().contains("comments")
            ) out.add(raw)
        }
        return out.sortedByDescending { it.length }.take(2).joinToString(" | ").take(300)
    }

    private fun walk(n: AccessibilityNodeInfo, f: (AccessibilityNodeInfo) -> Unit) {
        f(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, f) }
    }

    private fun find(root: AccessibilityNodeInfo, pred: (String, AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val res = ArrayList<AccessibilityNodeInfo>()
        walk(root) { n -> if (pred(label(n), n)) res.add(n) }
        return res
    }

    private fun label(n: AccessibilityNodeInfo): String =
        ((n.contentDescription ?: n.text)?.toString() ?: "").trim().lowercase()

    // ---------- input ----------
    private fun clickNode(n: AccessibilityNodeInfo) {
        var c: AccessibilityNodeInfo? = n
        var depth = 0
        while (c != null && depth < 5) {
            if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            c = c.parent; depth++
        }
        val b = Rect(); n.getBoundsInScreen(b)
        tap(b.exactCenterX() + Random.nextInt(-4, 5), b.exactCenterY() + Random.nextInt(-4, 5))
    }

    private fun tap(x: Float, y: Float) = gesture(Path().apply { moveTo(x, y) }, rnd(40, 90))

    private fun gesture(path: Path, durMs: Long) {
        val latch = CountDownLatch(1)
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durMs)).build()
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        if (ok) latch.await(durMs + 2000, TimeUnit.MILLISECONDS)
    }

    // ---------- util ----------
    private fun chance(pct: Int) = Random.nextInt(100) < pct
    private fun rnd(a: Long, b: Long) = Random.nextLong(a, b)
    private fun sleepMs(ms: Long) { if (!running) throw InterruptedException(); Thread.sleep(ms) }
}
