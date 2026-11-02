package com.ruvk.tube.data.api.rutube

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.ruvk.tube.data.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder

data class RutubeSearchResponse(
    @SerializedName("results") val results: List<RutubeVideoItem>?,
    @SerializedName("count") val count: Int = 0
)

data class RutubeVideoItem(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("duration") val duration: Int = 0,
    @SerializedName("description") val description: String?,
    @SerializedName("thumbnail_url") val thumbnailUrl: String?
)

data class RutubePlayOptionsResponse(
    @SerializedName("video_balancer") val videoBalancer: RutubeVideoBalancer?,
    @SerializedName("is_payed") val isPayed: Boolean = false,
    @SerializedName("is_deleted") val isDeleted: Boolean = false
)

data class RutubeVideoBalancer(
    @SerializedName("default") val defaultUrl: String?,
    @SerializedName("m3u8") val m3u8: String?
)

class RutubeClient(
    private val gson: Gson = Gson()
) {

    suspend fun searchVideos(query: String): List<RutubeVideoItem> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        // Публичный открытый эндпоинт поиска Rutube
        val url = "https://rutube.ru/api/search/video/?query=$encodedQuery&format=json"

        val request = Request.Builder()
            .url(url)
            .header("Referer", "https://rutube.ru/")
            .header("User-Agent", NetworkClient.USER_AGENT)
            .get()
            .build()

        try {
            NetworkClient.okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("RuVkTube", "Rutube search failed: HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                val parsed = gson.fromJson(body, RutubeSearchResponse::class.java)
                val items = parsed.results ?: emptyList()
                Log.d("RuVkTube", "Rutube found ${items.size} videos for query: '$query'")
                items
            }
        } catch (e: Exception) {
            Log.e("RuVkTube", "Error searching Rutube for '$query'", e)
            emptyList()
        }
    }

    suspend fun getStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        val url = "https://rutube.ru/api/play/options/$videoId/?format=json"

        val request = Request.Builder()
            .url(url)
            .header("Referer", "https://rutube.ru/")
            .header("User-Agent", NetworkClient.USER_AGENT)
            .get()
            .build()

        try {
            NetworkClient.okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val parsed = gson.fromJson(body, RutubePlayOptionsResponse::class.java)
                if (parsed.isDeleted || parsed.isPayed) return@withContext null
                parsed.videoBalancer?.defaultUrl ?: parsed.videoBalancer?.m3u8
            }
        } catch (e: Exception) {
            Log.e("RuVkTube", "Error getting Rutube stream for $videoId", e)
            null
        }
    }
}
