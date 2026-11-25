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

data class RutubeAuthor(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("name") val name: String? = null
)

data class RutubeVideoItem(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("duration") val duration: Int = 0,
    @SerializedName("description") val description: String?,
    @SerializedName("thumbnail_url") val thumbnailUrl: String?,
    @SerializedName("author") val author: RutubeAuthor? = null
)

data class RutubePlaylistItem(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("videos_count") val videosCount: Int = 0
)

data class RutubePlaylistsResponse(
    @SerializedName("results") val results: List<RutubePlaylistItem>? = null
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
        val parsed = executeGetJson(url, RutubeSearchResponse::class.java)
        val items = parsed?.results ?: emptyList()
        Log.d("RuVkTube", "Rutube found ${items.size} videos for query: '$query'")
        items
    }

    suspend fun getUserPlaylists(authorId: Long): List<RutubePlaylistItem> = withContext(Dispatchers.IO) {
        if (authorId <= 0L) return@withContext emptyList()
        val url = "https://rutube.ru/api/playlist/user/$authorId/?format=json&limit=50"
        val parsed = executeGetJson(url, RutubePlaylistsResponse::class.java)
        parsed?.results ?: emptyList()
    }

    suspend fun getPlaylistVideos(playlistId: Long): List<RutubeVideoItem> = withContext(Dispatchers.IO) {
        if (playlistId <= 0L) return@withContext emptyList()
        val url = "https://rutube.ru/api/playlist/custom/$playlistId/videos/?format=json&limit=100"
        val parsed = executeGetJson(url, RutubeSearchResponse::class.java)
        parsed?.results ?: emptyList()
    }

    suspend fun getStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        val url = "https://rutube.ru/api/play/options/$videoId/?format=json"
        val parsed = executeGetJson(url, RutubePlayOptionsResponse::class.java) ?: return@withContext null
        if (parsed.isDeleted || parsed.isPayed) return@withContext null
        parsed.videoBalancer?.defaultUrl ?: parsed.videoBalancer?.m3u8
    }

    private fun <T> executeGetJson(url: String, clazz: Class<T>): T? {
        val request = Request.Builder()
            .url(url)
            .header("Referer", "https://rutube.ru/")
            .header("User-Agent", NetworkClient.USER_AGENT)
            .get()
            .build()

        return try {
            NetworkClient.okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("RuVkTube", "Rutube HTTP ${response.code} for $url")
                    return null
                }
                val body = response.body?.string() ?: return null
                gson.fromJson(body, clazz)
            }
        } catch (e: Exception) {
            Log.e("RuVkTube", "Rutube network/parse error for $url", e)
            null
        }
    }
}
