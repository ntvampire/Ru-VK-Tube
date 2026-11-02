package com.ruvk.tube.data.api.rutube

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.ruvk.tube.data.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder

data class RutubeSearchResponse(
    @SerializedName("results") val results: List<RutubeVideoItem>?
)

data class RutubeVideoItem(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("duration") val duration: Int,
    @SerializedName("description") val description: String?,
    @SerializedName("thumbnail_url") val thumbnailUrl: String?
)

data class RutubePlayOptionsResponse(
    @SerializedName("video_balancer") val videoBalancer: RutubeVideoBalancer?,
    @SerializedName("is_payed") val isPayed: Boolean = false,
    @SerializedName("is_deleted") val isDeleted: Boolean = false
)

data class RutubeVideoBalancer(
    @SerializedName("m3u8") val m3u8: String?
)

class RutubeClient(
    private val gson: Gson = Gson()
) {

    suspend fun searchVideos(query: String): List<RutubeVideoItem> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://rutube.ru/api/video/search/?query=$encodedQuery&format=json"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        try {
            NetworkClient.okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val parsed = gson.fromJson(body, RutubeSearchResponse::class.java)
                parsed.results ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        val url = "https://rutube.ru/api/play/options/$videoId/?format=json"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        try {
            NetworkClient.okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val parsed = gson.fromJson(body, RutubePlayOptionsResponse::class.java)
                if (parsed.isDeleted || parsed.isPayed) return@withContext null
                parsed.videoBalancer?.m3u8
            }
        } catch (e: Exception) {
            null
        }
    }
}
