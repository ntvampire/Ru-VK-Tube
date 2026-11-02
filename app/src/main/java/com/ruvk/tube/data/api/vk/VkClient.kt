package com.ruvk.tube.data.api.vk

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ruvk.tube.data.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder
import java.util.regex.Pattern

data class VkVideoItem(
    val oid: String,
    val id: String,
    val title: String,
    val durationSeconds: Int,
    val thumbUrl: String?
)

data class VkStreamInfo(
    val hlsUrl: String?,
    val directUrls: Map<String, String>, // "1080" to url, "720" to url, etc.
    val bestQuality: String
)

class VkClient(
    private val gson: Gson = Gson()
) {

    private val videoExtRegex = Pattern.compile("""var\s+playerParams\s*=\s*(\{.*?\});\s*(?:var|function|</script>)""", Pattern.DOTALL)
    private val mobileVideoLinkRegex = Pattern.compile("""href=["']/video(-?\d+)_(\d+)["']""")
    private val durationRegex = Pattern.compile("""(\d+):(\d+)""")

    suspend fun searchVideos(query: String): List<VkVideoItem> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://m.vk.com/video?q=$encodedQuery&section=search"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NetworkClient.USER_AGENT)
            .get()
            .build()

        try {
            NetworkClient.okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val html = response.body?.string() ?: return@withContext emptyList()
                parseMobileSearchHtml(html)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun resolveStream(oid: String, id: String): VkStreamInfo? = withContext(Dispatchers.IO) {
        val embedUrl = "https://vk.com/video_ext.php?oid=$oid&id=$id"

        val request = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", NetworkClient.USER_AGENT)
            .get()
            .build()

        try {
            NetworkClient.okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val html = response.body?.string() ?: return@withContext null
                parsePlayerParams(html)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseMobileSearchHtml(html: String): List<VkVideoItem> {
        val items = mutableListOf<VkVideoItem>()
        val matcher = mobileVideoLinkRegex.matcher(html)
        val seen = mutableSetOf<String>()

        while (matcher.find()) {
            val oid = matcher.group(1) ?: continue
            val id = matcher.group(2) ?: continue
            val key = "${oid}_${id}"
            if (!seen.add(key)) continue

            // Извлечение окружающего текста для названия
            val startIdx = matcher.start()
            val chunk = html.substring(startIdx, (startIdx + 600).coerceAtMost(html.length))
            val title = extractTitleFromChunk(chunk) ?: "Видео $key"
            val duration = extractDurationFromChunk(chunk)

            items.add(
                VkVideoItem(
                    oid = oid,
                    id = id,
                    title = title,
                    durationSeconds = duration,
                    thumbUrl = null
                )
            )
        }
        return items
    }

    private fun extractTitleFromChunk(chunk: String): String? {
        val titleMatch = Pattern.compile("""class=["'](?:video_item__title|v_title)[^"']*["']>([^<]+)<""").matcher(chunk)
        if (titleMatch.find()) {
            return titleMatch.group(1)?.trim()
        }
        val altMatch = Pattern.compile("""alt=["']([^"']+)["']""").matcher(chunk)
        if (altMatch.find()) {
            return altMatch.group(1)?.trim()
        }
        return null
    }

    private fun extractDurationFromChunk(chunk: String): Int {
        val match = durationRegex.matcher(chunk)
        if (match.find()) {
            val m = match.group(1)?.toIntOrNull() ?: 0
            val s = match.group(2)?.toIntOrNull() ?: 0
            return m * 60 + s
        }
        return 0
    }

    private fun parsePlayerParams(html: String): VkStreamInfo? {
        val matcher = videoExtRegex.matcher(html)
        if (!matcher.find()) return null
        val jsonStr = matcher.group(1) ?: return null

        return try {
            val root = gson.fromJson(jsonStr, JsonObject::class.java)
            val paramsArray = root.getAsJsonArray("params") ?: return null
            if (paramsArray.size() == 0) return null
            val param = paramsArray.get(0).asJsonObject

            val hls = param.get("hls")?.asString
            val directUrls = mutableMapOf<String, String>()

            listOf("2160", "1440", "1080", "720", "480", "360", "240").forEach { q ->
                param.get("url$q")?.asString?.let { url ->
                    directUrls[q] = url
                }
            }

            val bestQuality = when {
                directUrls.containsKey("2160") -> "4K"
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
            null
        }
    }
}
