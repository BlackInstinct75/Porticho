package com.example.rola

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONObject

/** WebView que aloja el reproductor IFrame de YouTube (assets/player.html). */
class YtView(ctx: Context, private val cb: Callbacks) {
    interface Callbacks {
        fun onReady()
        fun onInfo(i: Info)
        fun onState(s: Int)
        fun onTime(cur: Double, dur: Double)
        fun onError(code: Int)
    }

    data class Info(val id: String, val title: String, val author: String, val playlist: List<String>, val index: Int)

    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    val web: WebView = WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        webChromeClient = WebChromeClient()
        addJavascriptInterface(Bridge(), "Android")
        // YouTube exige identificar la app: el origen es https://<applicationId>.
        // Usar https://www.youtube.com provoca el error 152; no usar origen, el 153.
        val origin = "https://" + ctx.packageName
        val html = ctx.assets.open("player.html").bufferedReader().use { it.readText() }
            .replace("__ORIGIN__", origin)
        loadDataWithBaseURL(origin, html, "text/html", "utf-8", null)
    }

    fun js(code: String) { main.post { web.evaluateJavascript(code, null) } }

    private inner class Bridge {
        @JavascriptInterface fun onReady() { main.post { cb.onReady() } }
        @JavascriptInterface fun onState(s: Int) { main.post { cb.onState(s) } }
        @JavascriptInterface fun onError(c: Int) { main.post { cb.onError(c) } }
        @JavascriptInterface fun onTime(c: Double, d: Double) { main.post { cb.onTime(c, d) } }
        @JavascriptInterface fun onInfo(json: String) {
            val o = JSONObject(json)
            val a = o.optJSONArray("playlist")
            val info = Info(
                o.optString("id"), o.optString("title"), o.optString("author"),
                List(a?.length() ?: 0) { a!!.getString(it) }, o.optInt("index", -1)
            )
            main.post { cb.onInfo(info) }
        }
    }
}