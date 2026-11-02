package com.ruvk.tube.data.api.vk

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
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
import kotlin.coroutines.resume

/**
 * Фоновый (Headless) WebView для анонимного поиска и получения потоков VK Video
 * в обход защиты Kittenx (WAF 418).
 */
class VkWebViewResolver(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebView(): WebView {
        if (webView == null) {
            webView = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }
        }
        return webView!!
    }

    suspend fun searchVideos(query: String): List<VkVideoItem> = withTimeoutOrNull(7000) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val searchUrl = "https://m.vk.com/video?q=$encodedQuery&section=search"

                val wv = getOrCreateWebView()
                var resumed = false

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Задержка 500мс для гидратации динамического DOM
                        mainHandler.postDelayed({
                            if (resumed || !continuation.isActive) return@postDelayed

                            val jsExtract = """
                                (function() {
                                    var items = [];
                                    var links = document.querySelectorAll('a[href*="/video"]');
                                    var seen = {};
                                    for (var i = 0; i < links.length; i++) {
                                        var a = links[i];
                                        var m = a.href.match(/video(-?\d+)_(\d+)/);
                                        if (m) {
                                            var key = m[1] + '_' + m[2];
                                            if (!seen[key]) {
                                                seen[key] = true;
                                                var title = a.innerText || a.getAttribute('title') || '';
                                                title = title.replace(/\n+/g, ' ').trim();
                                                if (title.length > 2) {
                                                    items.push({ oid: m[1], id: m[2], title: title });
                                                }
                                            }
                                        }
                                    }
                                    return JSON.stringify(items);
                                })();
                            """.trimIndent()

                            wv.evaluateJavascript(jsExtract) { result ->
                                if (resumed || !continuation.isActive) return@evaluateJavascript
                                resumed = true

                                val items = mutableListOf<VkVideoItem>()
                                try {
                                    if (!result.isNullOrBlank() && result != "null") {
                                        // evaluateJavascript возвращает JSON в виде экранированной строки
                                        val unescaped = gson.fromJson(result, String::class.java)
                                        val array = gson.fromJson(unescaped, JsonArray::class.java)
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

                                Log.d("RuVkTube", "VK Headless WebView found ${items.size} videos for '$query'")
                                continuation.resume(items)
                            }
                        }, 600)
                    }
                }

                wv.loadUrl(searchUrl)

                continuation.invokeOnCancellation {
                    resumed = true
                    wv.stopLoading()
                }
            }
        }
    } ?: emptyList()

    suspend fun resolveStream(oid: String, id: String): VkStreamInfo? = withTimeoutOrNull(6000) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val embedUrl = "https://vk.com/video_ext.php?oid=$oid&id=$id"
                val wv = getOrCreateWebView()
                var resumed = false

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        mainHandler.postDelayed({
                            if (resumed || !continuation.isActive) return@postDelayed

                            val jsExtract = """
                                (function() {
                                    try {
                                        if (typeof playerParams !== 'undefined') return JSON.stringify(playerParams);
                                        if (window.playerParams) return JSON.stringify(window.playerParams);
                                    } catch(e) {}
                                    return "";
                                })();
                            """.trimIndent()

                            wv.evaluateJavascript(jsExtract) { result ->
                                if (resumed || !continuation.isActive) return@evaluateJavascript
                                resumed = true

                                var streamInfo: VkStreamInfo? = null
                                try {
                                    if (!result.isNullOrBlank() && result != "null" && result != "\"\"") {
                                        val jsonStr = gson.fromJson(result, String::class.java)
                                        val root = gson.fromJson(jsonStr, JsonObject::class.java)
                                        val paramsArray = root.getAsJsonArray("params")
                                        if (paramsArray != null && paramsArray.size() > 0) {
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

                                            streamInfo = VkStreamInfo(hls, directUrls, bestQuality)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("RuVkTube", "Error extracting VK playerParams", e)
                                }

                                continuation.resume(streamInfo)
                            }
                        }, 400)
                    }
                }

                wv.loadUrl(embedUrl)

                continuation.invokeOnCancellation {
                    resumed = true
                    wv.stopLoading()
                }
            }
        }
    }
}
