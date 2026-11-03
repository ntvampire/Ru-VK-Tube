package com.ruvk.tube.data.update

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.ruvk.tube.BuildConfig
import com.ruvk.tube.data.local.AppPreferences
import com.ruvk.tube.data.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

data class UpdateInfo(
    @SerializedName("versionCode") val versionCode: Int,
    @SerializedName("versionName") val versionName: String,
    @SerializedName("apkUrl") val apkUrl: String,
    @SerializedName("changelog") val changelog: String?
)

object UpdateManager {

    private const val TAG = "UpdateManager"
    const val PRIMARY_UPDATE_URL = "https://ntvampire.github.io/Ru-VK-Tube/update.json"
    const val FALLBACK_RELEASE_URL = "https://api.github.com/repos/ntvampire/Ru-VK-Tube/releases/latest"

    /**
     * Проверяет наличие новой версии на сервере.
     * Возвращает UpdateInfo, если на сервере versionCode > текущего BuildConfig.VERSION_CODE.
     */
    suspend fun checkUpdate(force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && (now - AppPreferences.lastUpdateCheck < 24 * 60 * 60 * 1000L)) {
            return@withContext null
        }
        AppPreferences.lastUpdateCheck = now

        // 1. Сначала пробуем *.github.io/Ru-VK-Tube/update.json
        var update = fetchFromGithubIo()

        // 2. Резерв: GitHub API releases/latest
        if (update == null) {
            update = fetchFromGithubApi()
        }

        if (update != null && update.versionCode > BuildConfig.VERSION_CODE) {
            Log.d(TAG, "Найдено обновление: v${update.versionName} (код ${update.versionCode}), текущая: ${BuildConfig.VERSION_CODE}")
            update
        } else {
            null
        }
    }

    private fun fetchFromGithubIo(): UpdateInfo? {
        return try {
            val request = Request.Builder()
                .url(PRIMARY_UPDATE_URL)
                .header("User-Agent", NetworkClient.USER_AGENT)
                .build()

            NetworkClient.okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return null
                    Gson().fromJson(body, UpdateInfo::class.java)
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка загрузки с github.io: ${e.message}")
            null
        }
    }

    private fun fetchFromGithubApi(): UpdateInfo? {
        return try {
            val request = Request.Builder()
                .url(FALLBACK_RELEASE_URL)
                .header("User-Agent", NetworkClient.USER_AGENT)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            NetworkClient.okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = Gson().fromJson(body, Map::class.java)

                val tagName = json["tag_name"] as? String ?: ""
                val bodyText = json["body"] as? String ?: ""
                val assets = json["assets"] as? List<*> ?: emptyList<Any>()

                var downloadUrl = ""
                for (asset in assets) {
                    val aMap = asset as? Map<*, *> ?: continue
                    val name = aMap["name"] as? String ?: ""
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = aMap["browser_download_url"] as? String ?: ""
                        break
                    }
                }

                if (downloadUrl.isBlank()) return null

                val cleanVer = tagName.removePrefix("v").trim()
                val parts = cleanVer.split(".")
                val parsedCode = if (parts.size >= 3) {
                    val major = parts[0].toIntOrNull() ?: 1
                    val minor = parts[1].toIntOrNull() ?: 0
                    val patch = parts[2].toIntOrNull() ?: 0
                    major * 100 + minor * 10 + patch
                } else {
                    BuildConfig.VERSION_CODE + 1
                }

                UpdateInfo(
                    versionCode = parsedCode,
                    versionName = cleanVer,
                    apkUrl = downloadUrl,
                    changelog = bodyText
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка загрузки с GitHub API: ${e.message}")
            null
        }
    }

    /**
     * Загружает APK с информированием о прогрессе (0..100%).
     */
    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", NetworkClient.USER_AGENT)
                .build()

            val response = NetworkClient.okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body ?: return@withContext null
            val contentLength = body.contentLength()

            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
            val destFile = File(dir, "RuVkTube-update.apk")
            if (destFile.exists()) destFile.delete()

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (contentLength > 0) {
                            val progress = ((totalBytesRead * 100) / contentLength).toInt()
                            withContext(Dispatchers.Main) {
                                onProgress(progress)
                            }
                        }
                    }
                    output.flush()
                }
            }

            destFile
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка скачивания APK: ${e.message}", e)
            null
        }
    }

    /**
     * Запускает системный диалог установки APK через FileProvider.
     */
    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        context.startActivity(intent)
    }
}
