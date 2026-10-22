package app.nut.browser

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import java.net.URLEncoder

/**
 * Nut — เบราว์เซอร์เรียบง่าย พร้อมแยกจอ 2/3/4 และปุ่มย้อน/ข้าม 10 วินาทีมุมล่างซ้าย
 * ทั้งหมดสร้างด้วยโค้ด (ไม่มีไฟล์ layout XML) เพื่อให้ไฟล์น้อยและแก้ง่าย
 */
class MainActivity : Activity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val BLUE = 0xFF0071E3.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private val panes = mutableListOf<Pane>()
    private var count = 1
    private var variant = 0
    private lateinit var active: Pane

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var skipOverlay: LinearLayout
    private lateinit var fullscreenHost: FrameLayout
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- ช่องเบราว์เซอร์ 1 ช่อง
    inner class Pane {
        val web = WebView(this@MainActivity)
        val frame = FrameLayout(this@MainActivity)

        init {
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (active === this@Pane) showUrl(url)
                }
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    setActive(this@Pane)
                    showFullscreen(view, callback)
                }

                override fun onHideCustomView() {
                    hideFullscreen()
                }
            }
            web.setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN && active !== this) setActive(this)
                false
            }
            frame.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
            loadHome()
        }

        fun loadHome() {
            web.loadDataWithBaseURL("https://nut.local/", HOME_HTML, "text/html", "utf-8", null)
        }
    }

    // ---------------------------------------------------------------- เริ่มแอป
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        content = FrameLayout(this)
        column.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        column.addView(buildBottomBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        fullscreenHost = FrameLayout(this)
        fullscreenHost.setBackgroundColor(Color.BLACK)
        fullscreenHost.visibility = View.GONE
        root.addView(fullscreenHost, FrameLayout.LayoutParams(MATCH, MATCH))

        skipOverlay = buildSkipOverlay()
        root.addView(skipOverlay, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START))
        updateOverlayMargin()

        setContentView(root)
        relayout()
        handler.postDelayed(poll, 1500)
    }

    // ---------------------------------------------------------------- แถบล่าง
    private fun buildBottomBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.VERTICAL
        bar.setBackgroundColor(0xFFF6F6F8.toInt())
        bar.setPadding(dp(12), dp(8), dp(12), dp(4))

        urlBar = EditText(this)
        urlBar.hint = "ค้นหาหรือป้อนที่อยู่เว็บ"
        urlBar.isSingleLine = true
        urlBar.textSize = 16f
        urlBar.setTextColor(0xFF1D1D1F.toInt())
        urlBar.setHintTextColor(0xFF636366.toInt())
        urlBar.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlBar.imeOptions = EditorInfo.IME_ACTION_GO
        urlBar.setPadding(dp(14), 0, dp(14), 0)
        urlBar.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(0xFFE9E9ED.toInt())
        }
        urlBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                go(v.text.toString())
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
                true
            } else false
        }
        urlBar.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) (v as EditText).selectAll() }
        bar.addView(urlBar, LinearLayout.LayoutParams(MATCH, dp(44)))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(barButton("‹", "ย้อนกลับ") { active.web.goBack() }, weight())
        row.addView(barButton("›", "ไปข้างหน้า") { active.web.goForward() }, weight())
        row.addView(barButton("⟳", "โหลดใหม่") { active.web.reload() }, weight())
        row.addView(barButton("⊞", "แยกจอ") { v -> showSplitMenu(v) }, weight())
        row.addView(barButton("⇄", "สลับรูปแบบการวาง") { variant = 1 - variant; relayout() }, weight())
        bar.addView(row, LinearLayout.LayoutParams(MATCH, dp(52)))
        return bar
    }

    private fun weight() = LinearLayout.LayoutParams(0, MATCH, 1f)

    private fun barButton(label: String, desc: String, onClick: (View) -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 24f
        t.gravity = Gravity.CENTER
        t.setTextColor(BLUE)
        t.contentDescription = desc
        t.minimumWidth = dp(48)
        t.minimumHeight = dp(48)
        t.setOnClickListener { onClick(it) }
        return t
    }

    private fun showSplitMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        for (n in 1..4) m.menu.add(0, n, n, if (n == 1) "1 จอ (ปกติ)" else "$n จอ")
        m.setOnMenuItemClickListener {
            count = it.itemId
            variant = 0
            relayout()
            true
        }
        m.show()
    }

    // ---------------------------------------------------------------- ปุ่มย้อน/ข้าม 10 วินาที (มุมล่างซ้าย)
    private fun buildSkipOverlay(): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(0xB3000000.toInt())
        }
        box.setPadding(dp(4), dp(2), dp(4), dp(2))
        box.addView(skipButton("⟲ 10", "ย้อนกลับ 10 วินาที", -10))
        box.addView(skipButton("10 ⟳", "ข้ามไปข้างหน้า 10 วินาที", 10))
        box.visibility = View.GONE
        return box
    }

    private fun skipButton(label: String, desc: String, seconds: Int): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 16f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.contentDescription = desc
        t.minimumWidth = dp(64)
        t.minimumHeight = dp(48)
        t.setOnClickListener { skip(seconds) }
        return t
    }

    private fun skip(seconds: Int) {
        val js = "(function(){var s=$seconds;" +
            "function f(d){var v=d.querySelectorAll('video');" +
            "for(var i=0;i<v.length;i++){try{v[i].currentTime=Math.max(0,v[i].currentTime+s);}catch(e){}}" +
            "var fr=d.querySelectorAll('iframe');" +
            "for(var j=0;j<fr.length;j++){try{f(fr[j].contentDocument);}catch(e){}}}" +
            "f(document);})()"
        active.web.evaluateJavascript(js, null)
    }

    private val poll = object : Runnable {
        override fun run() {
            val js = "(function(){function h(d){if(d.querySelectorAll('video').length>0)return true;" +
                "var fr=d.querySelectorAll('iframe');" +
                "for(var j=0;j<fr.length;j++){try{if(h(fr[j].contentDocument))return true;}catch(e){}}" +
                "return false;}return h(document);})()"
            if (this@MainActivity::active.isInitialized) {
                active.web.evaluateJavascript(js) { r ->
                    skipOverlay.visibility =
                        if (r == "true" || customView != null) View.VISIBLE else View.GONE
                }
            }
            handler.postDelayed(this, 1500)
        }
    }

    private fun updateOverlayMargin() {
        val lp = skipOverlay.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = dp(12)
        lp.bottomMargin = if (customView != null) dp(28) else dp(112)
        skipOverlay.layoutParams = lp
    }

    // ---------------------------------------------------------------- วิดีโอเต็มจอ
    private fun showFullscreen(view: View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            cb.onCustomViewHidden()
            return
        }
        customView = view
        customCb = cb
        fullscreenHost.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        fullscreenHost.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        skipOverlay.visibility = View.VISIBLE
        skipOverlay.bringToFront()
        updateOverlayMargin()
    }

    private fun hideFullscreen() {
        val v = customView ?: return
        fullscreenHost.removeView(v)
        fullscreenHost.visibility = View.GONE
        customCb?.onCustomViewHidden()
        customView = null
        customCb = null
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        updateOverlayMargin()
    }

    // ---------------------------------------------------------------- แยกจอ
    private fun relayout() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        while (panes.size < count) panes.add(Pane())
        if (!this::active.isInitialized) active = panes[0]

        panes.forEach { (it.frame.parent as? ViewGroup)?.removeView(it.frame) }
        content.removeAllViews()

        val shown = panes.take(count)
        content.addView(build(shown.map { it.frame }, land), FrameLayout.LayoutParams(MATCH, MATCH))
        panes.forEachIndexed { i, p -> if (i < count) p.web.onResume() else p.web.onPause() }

        if (active !in shown) active = shown[0]
        refreshHighlights()
        showUrl(active.web.url ?: "")
    }

    private fun box(horizontal: Boolean, vararg kids: View): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for (k in kids) {
            val lp = if (horizontal) LinearLayout.LayoutParams(0, MATCH, 1f)
            else LinearLayout.LayoutParams(MATCH, 0, 1f)
            l.addView(k, lp)
        }
        return l
    }

    /** รูปแบบการวางตรงกับดีไซน์: ตั้ง/นอน × 2/3/4 จอ × 2 รูปแบบ */
    private fun build(v: List<View>, land: Boolean): View {
        return when (v.size) {
            1 -> v[0]
            2 -> box((variant == 0) == land, v[0], v[1])
            3 -> {
                val flat = variant == 0
                if (!land) {
                    if (flat) box(false, v[0], v[1], v[2]) else box(false, v[0], box(true, v[1], v[2]))
                } else {
                    if (flat) box(true, v[0], v[1], v[2]) else box(true, v[0], box(false, v[1], v[2]))
                }
            }
            else -> {
                if (variant == 0) box(false, box(true, v[0], v[1]), box(true, v[2], v[3]))
                else box(land, v[0], v[1], v[2], v[3])
            }
        }
    }

    private fun setActive(p: Pane) {
        active = p
        refreshHighlights()
        showUrl(p.web.url ?: "")
    }

    private fun refreshHighlights() {
        val pad = if (count > 1) dp(2) else 0
        panes.forEach {
            it.frame.setPadding(pad, pad, pad, pad)
            it.frame.setBackgroundColor(if (count > 1 && it === active) BLUE else Color.TRANSPARENT)
        }
    }

    // ---------------------------------------------------------------- ที่อยู่เว็บ
    private fun showUrl(url: String) {
        val hide = url.isEmpty() || url.startsWith("data:") || url == "about:blank" ||
            url.startsWith("https://nut.local")
        urlBar.setText(if (hide) "" else url)
    }

    private fun go(input: String) {
        val s = input.trim()
        if (s.isEmpty()) return
        val url = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            !s.contains(' ') && s.contains('.') -> "https://$s"
            else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(s, "UTF-8")
        }
        active.web.loadUrl(url)
    }

    // ---------------------------------------------------------------- วงจรชีวิต
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        relayout()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> hideFullscreen()
            active.web.canGoBack() -> active.web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        panes.forEach { it.web.onPause() }
    }

    override fun onResume() {
        super.onResume()
        panes.forEachIndexed { i, p -> if (i < count) p.web.onResume() }
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        panes.forEach { it.web.destroy() }
        super.onDestroy()
    }

    companion object {
        private const val HOME_HTML = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{margin:0;font-family:-apple-system,Roboto,sans-serif;background:#fff;color:#1d1d1f;
display:flex;flex-direction:column;align-items:center;padding:48px 20px}
.logo{width:56px;height:56px;border-radius:14px;background:linear-gradient(135deg,#e7b77a,#b9742f);
display:flex;align-items:center;justify-content:center}
.logo i{width:28px;height:28px;border-radius:50%;background:#fff8ee;display:block}
h1{font-size:26px;margin:10px 0 18px;font-weight:600}
form{width:100%;max-width:420px}
input{width:100%;box-sizing:border-box;height:46px;border-radius:14px;border:1px solid #e3e3e8;
background:#f5f5f7;padding:0 16px;font-size:16px}
.fav{display:flex;gap:14px;margin-top:28px;flex-wrap:wrap;justify-content:center}
.fav a{color:#0a5bd3;text-decoration:none;font-size:14px;padding:10px 14px;background:#e8f0fe;border-radius:12px}
</style></head><body>
<div class="logo"><i></i></div><h1>Nut</h1>
<form action="https://duckduckgo.com/" method="get">
<input name="q" placeholder="ค้นหาด้วย Nut" aria-label="ค้นหา"></form>
<div class="fav">
<a href="https://www.youtube.com/">YouTube</a>
<a href="https://th.wikipedia.org/">Wikipedia</a>
<a href="https://www.openstreetmap.org/">แผนที่</a>
</div></body></html>"""
    }
}
