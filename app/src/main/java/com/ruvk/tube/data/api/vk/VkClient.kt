package com.ruvk.tube.data.api.vk

import android.os.Build
import android.text.Html
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ruvk.tube.data.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset

data class VkVideoItem(
    val oid: String,
    val id: String,
    val title: String,
    val durationSeconds: Int,
    val thumbUrl: String?
)

data class VkStreamInfo(
    val hlsUrl: String?,
    val directUrls: Map<String, String>, // "2160", "1080", "720", etc.
    val bestQuality: String
)

/**
 * Высокопроизводительный прямой клиент для VK Video.
 * Работает через быстрые HTTP POST запросы к al_video.php без WebView и сторонней авторизации.
 */
class VkClient(
    private val okHttpClient: OkHttpClient = NetworkClient.okHttpClient,
    private val gson: Gson = Gson()
) {

    companion object {
        private const val TAG = "RuVkTube"
        private const val ENDPOINT = "https://vk.com/al_video.php"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private val CP1251 = Charset.forName("windows-1251")
    }

    suspend fun searchVideos(query: String): List<VkVideoItem> = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder(CP1251)
            .add("al", "1")
            .add("act", "search_video")
            .add("q", query)
            .build()

        val request = Request.Builder()
            .url(ENDPOINT)
            .post(formBody)
            .header("User-Agent", USER_AGENT)
            .header("Referer", ENDPOINT)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "VK search HTTP error ${response.code} for '$query'")
                return@withContext emptyList()
            }

            val bodyBytes = response.body?.bytes() ?: return@withContext emptyList()
            val jsonText = String(bodyBytes, CP1251)

            val items = parseSearchPayload(jsonText)
            Log.d(TAG, "VK search for '$query': successfully returned ${items.size} videos")
            items
        } catch (e: Exception) {
            Log.e(TAG, "VK search network error for '$query'", e)
            emptyList()
        }
    }

    suspend fun resolveStream(oid: String, id: String): VkStreamInfo? = withContext(Dispatchers.IO) {
        val videoKey = "${oid}_${id}"
        val formBody = FormBody.Builder(CP1251)
            .add("al", "1")
            .add("act", "show")
            .add("video", videoKey)
            .build()

        val request = Request.Builder()
            .url(ENDPOINT)
            .post(formBody)
            .header("User-Agent", USER_AGENT)
            .header("Referer", ENDPOINT)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "VK resolveStream HTTP error ${response.code} for $videoKey")
                return@withContext null
            }

            val bodyBytes = response.body?.bytes() ?: return@withContext null
            val jsonText = String(bodyBytes, CP1251)

            parsePlayerPayload(jsonText)
        } catch (e: Exception) {
            Log.e(TAG, "VK resolveStream error for $videoKey", e)
            null
        }
    }

    private fun parseSearchPayload(jsonText: String): List<VkVideoItem> {
        val items = mutableListOf<VkVideoItem>()
        try {
            val root = JsonParser.parseString(jsonText).asJsonObject
            val payload = root.getAsJsonArray("payload") ?: return emptyList()
            if (payload.size() < 2) return emptyList()

            val inner = payload.get(1).asJsonArray
            if (inner.size() < 3) return emptyList()

            val dataObj = inner.get(2).asJsonObject
            val list = dataObj.getAsJsonArray("list") ?: return emptyList()

            for (elem in list) {
                if (!elem.isJsonArray) continue
                val arr = elem.asJsonArray
                if (arr.size() < 4) continue

                val oid = arr.get(0).asString
                val id = arr.get(1).asString
                val thumb = if (arr.get(2).isJsonPrimitive) arr.get(2).asString else null
                val rawTitle = arr.get(3).asString
                val cleanTitle = cleanHtmlEntities(rawTitle)

                var durationSec = 0
                if (arr.size() > 5 && arr.get(5).isJsonPrimitive) {
                    durationSec = parseDurationString(arr.get(5).asString)
                }
                if (durationSec == 0 && arr.size() > 14 && arr.get(14).isJsonObject) {
                    val durObj = arr.get(14).asJsonObject
                    durObj.get("duration")?.asString?.let { textDur ->
                        durationSec = parseRussianDurationText(textDur)
                    }
                }

                if (cleanTitle.isNotBlank()) {
                    items.add(
                        VkVideoItem(
                            oid = oid,
                            id = id,
                            title = cleanTitle,
                            durationSeconds = durationSec,
                            thumbUrl = thumb
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "VK parseSearchPayload error", e)
        }
        return items
    }

    private fun parsePlayerPayload(jsonText: String): VkStreamInfo? {
        return try {
            val root = JsonParser.parseString(jsonText).asJsonObject
            val payload = root.getAsJsonArray("payload") ?: return null
            if (payload.size() < 2) return null

            val inner = payload.get(1).asJsonArray
            if (inner.size() == 0) return null

            val opts = inner.get(inner.size() - 1).asJsonObject
            val player = opts.getAsJsonObject("player") ?: return null
            val paramsArray = player.getAsJsonArray("params") ?: return null
            if (paramsArray.size() == 0) return null

            val param = paramsArray.get(0).asJsonObject
            val hls = param.get("hls")?.asString
            val directUrls = mutableMapOf<String, String>()

            listOf("2160", "1440", "1080", "720", "480", "360", "240").forEach { q ->
                param.get("url$q")?.asString?.let { u ->
                    directUrls[q] = u
                }
            }

            val bestQuality = when {
                directUrls.containsKey("2160") -> "4K"
                directUrls.containsKey("1440") -> "2K"
                directUrls.containsKey("1080") -> "1080p"
                directUrls.containsKey("720") -> "720p"
                directUrls.containsKey("480") -> "480p"
                hls != null -> "Auto (HLS)"
                else -> "SD"
            }

            VkStreamInfo(
                hlsUrl = hls,
                directUrls = directUrls,
                bestQuality = bestQuality
            )
        } catch (e: Exception) {
            Log.e(TAG, "VK parsePlayerPayload error", e)
            null
        }
    }

    private fun cleanHtmlEntities(text: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString().trim()
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(text).toString().trim()
            }
        } catch (e: Exception) {
            text.replace("&#33;", "!")
                .replace("&#036;", "$")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .trim()
        }
    }

    private fun parseDurationString(str: String): Int {
        val trimmed = str.trim()
        val asInt = trimmed.toIntOrNull()
        if (asInt != null && asInt > 0) return asInt

        val parts = trimmed.split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    private fun parseRussianDurationText(text: String): Int {
        var total = 0
        val hoursMatch = Regex("(\\d+)\\s*(?:час|ч)").find(text)
        if (hoursMatch != null) {
            total += (hoursMatch.groupValues[1].toIntOrNull() ?: 0) * 3600
        }
        val minMatch = Regex("(\\d+)\\s*(?:мин|м)").find(text)
        if (minMatch != null) {
            total += (minMatch.groupValues[1].toIntOrNull() ?: 0) * 60
        }
        val secMatch = Regex("(\\d+)\\s*(?:сек|с)").find(text)
        if (secMatch != null) {
            total += (secMatch.groupValues[1].toIntOrNull() ?: 0)
        }
        return total
    }
}
