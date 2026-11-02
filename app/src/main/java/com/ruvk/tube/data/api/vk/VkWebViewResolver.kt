package com.ruvk.tube.data.api.vk

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Headless WebView-резолвер для VK Video.
 * Проходит проверки Kittenx (WAF) и динамически извлекает результаты поиска
 * и параметры плеера через JavaScriptInterface с умным поллингом DOM.
 */
class VkWebViewResolver(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private var webView: WebView? = null

    // Мост между JS внутри WebView и Kotlin-корутинами
    private var searchCallback: ((String) -> Unit)? = null
    private var playerCallback: ((String) -> Unit)? = null

    inner class AndroidBridge {
        @JavascriptInterface
        fun onSearchResults(json: String) {
            mainHandler.post {
                searchCallback?.invoke(json)
                searchCallback = null
            }
        }

        @JavascriptInterface
        fun onPlayerParams(json: String) {
            mainHandler.post {
                playerCallback?.invoke(json)
                playerCallback = null
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebView(): WebView {
        if (webView == null) {
            webView = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

                webChromeClient = WebChromeClient()

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                addJavascriptInterface(AndroidBridge(), "AndroidBridge")
            }
        }
        return webView!!
    }

    suspend fun searchVideos(query: String): List<VkVideoItem> = withTimeoutOrNull(9000) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val searchUrl = "https://vkvideo.ru/search?q=$encodedQuery"

                val wv = getOrCreateWebView()
                val isResumed = AtomicBoolean(false)

                searchCallback = { json ->
                    if (isResumed.compareAndSet(false, true) && continuation.isActive) {
                        val items = parseSearchJson(json)
                        Log.d("RuVkTube", "VK WebView successfully returned ${items.size} videos for '$query'")
                        continuation.resume(items)
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.d("RuVkTube", "VK onPageFinished: $url")

                        // Инжектируем JS с поллингом DOM каждые 300мс (до 15 попыток)
                        val jsPoll = """
                            (function() {
                                if (window._vkSearchPoller) clearInterval(window._vkSearchPoller);
                                var attempts = 0;
                                window._vkSearchPoller = setInterval(function() {
                                    attempts++;
                                    var links = document.querySelectorAll('a[href*="/video"], a[href*="video-"], [data-video-id]');
                                    if (links.length > 0 || attempts >= 16) {
                                        clearInterval(window._vkSearchPoller);
                                        var items = [];
                                        var seen = {};
                                        for (var i = 0; i < links.length; i++) {
                                            var el = links[i];
                                            var href = el.getAttribute('href') || el.getAttribute('data-video-id') || el.href || '';
                                            var m = href.match(/video(-?\d+)_(\d+)/);
                                            if (m) {
                                                var key = m[1] + '_' + m[2];
                                                if (!seen[key]) {
                                                    seen[key] = true;
                                                    var title = el.innerText || el.getAttribute('title') || el.getAttribute('aria-label') || '';
                                                    title = title.replace(/\n+/g, ' ').trim();
                                                    if (title.length > 2) {
                                                        items.push({ oid: m[1], id: m[2], title: title });
                                                    }
                                                }
                                            }
                                        }
                                        if (window.AndroidBridge) {
                                            window.AndroidBridge.onSearchResults(JSON.stringify(items));
                                        }
                                    }
                                }, 300);
                            })();
                        """.trimIndent()

                        wv.evaluateJavascript(jsPoll, null)
                    }
                }

                wv.loadUrl(searchUrl)

                continuation.invokeOnCancellation {
                    isResumed.set(true)
                    searchCallback = null
                    wv.stopLoading()
                }
            }
        }
    } ?: emptyList()

    suspend fun resolveStream(oid: String, id: String): VkStreamInfo? = withTimeoutOrNull(7000) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val embedUrl = "https://vk.com/video_ext.php?oid=$oid&id=$id"
                val wv = getOrCreateWebView()
                val isResumed = AtomicBoolean(false)

                playerCallback = { json ->
                    if (isResumed.compareAndSet(false, true) && continuation.isActive) {
                        val streamInfo = parsePlayerParamsJson(json)
                        continuation.resume(streamInfo)
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val jsPoll = """
                            (function() {
                                if (window._vkPlayerPoller) clearInterval(window._vkPlayerPoller);
                                var attempts = 0;
                                window._vkPlayerPoller = setInterval(function() {
                                    attempts++;
                                    var p = (typeof playerParams !== 'undefined' ? playerParams : (window.playerParams || null));
                                    if (p || attempts >= 15) {
                                        clearInterval(window._vkPlayerPoller);
                                        if (window.AndroidBridge) {
                                            window.AndroidBridge.onPlayerParams(p ? JSON.stringify(p) : "");
                                        }
                                    }
                                }, 250);
                            })();
                        """.trimIndent()

                        wv.evaluateJavascript(jsPoll, null)
                    }
                }

                wv.loadUrl(embedUrl)

                continuation.invokeOnCancellation {
                    isResumed.set(true)
                    playerCallback = null
                    wv.stopLoading()
                }
            }
        }
    }

    private fun parseSearchJson(json: String): List<VkVideoItem> {
        val items = mutableListOf<VkVideoItem>()
        try {
            if (json.isNotBlank() && json != "null") {
                val array = gson.fromJson(json, JsonArray::class.java)
                for (elem in array) {
                    val obj = elem.asJsonObject
                    items.add(
                        VkVideoItem(
                            oid = obj.get("oid").asString,
                            id = obj.get("id").asString,
                            title = obj.get("title").asString,
                            durationSeconds = 0,
                            thumbUrl = null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("RuVkTube", "Error parsing VK search JSON", e)
        }
        return items
    }

    private fun parsePlayerParamsJson(json: String): VkStreamInfo? {
        if (json.isBlank() || json == "null") return null
        return try {
            val root = gson.fromJson(json, JsonObject::class.java)
            val paramsArray = root.getAsJsonArray("params") ?: return null
            if (paramsArray.size() == 0) return null
            val param = paramsArray.get(0).asJsonObject

            val hls = param.get("hls")?.asString
            val directUrls = mutableMapOf<String, String>()

            listOf("2160", "1440", "1080", "720", "480", "360", "240").forEach { q ->
                param.get("url$q")?.asString?.let { u -> directUrls[q] = u }
            }

            val bestQuality = when {
                directUrls.containsKey("2160") -> "4K"
                directUrls.containsKey("1080") -> "1080p"
                directUrls.containsKey("720") -> "720p"
                directUrls.containsKey("480") -> "480p"
                hls != null -> "Auto (HLS)"
                else -> "SD"
            }

            VkStreamInfo(hls, directUrls, bestQuality)
        } catch (e: Exception) {
            Log.e("RuVkTube", "Error extracting VK playerParams JSON", e)
            null
        }
    }
}
