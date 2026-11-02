package com.ruvk.tube.player

import android.content.Context
import android.graphics.Point
import android.view.WindowManager

object DeviceCapabilityHelper {

    /**
     * Возвращает максимальную высоту экрана устройства (2160 для 4K, 1080 для FHD, 720 для HD).
     */
    fun getMaxDisplayHeight(context: Context): Int {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return 1080
            val display = wm.defaultDisplay
            val size = Point()
            display.getRealSize(size)
            val maxDim = maxOf(size.x, size.y)
            val minDim = minOf(size.x, size.y)

            when {
                maxDim >= 3800 || minDim >= 2100 -> 2160 // 4K UHD
                maxDim >= 1900 || minDim >= 1000 -> 1080 // Full HD
                maxDim >= 1200 || minDim >= 700  -> 720  // HD
                else -> 1080
            }
        } catch (e: Exception) {
            1080
        }
    }

    /**
     * Преобразует строковое качество ("4K", "1080p", "720p", "480p") в число строк по вертикали.
     */
    fun qualityToHeight(quality: String): Int {
        val q = quality.uppercase()
        return when {
            q.contains("2160") || q.contains("4K") -> 2160
            q.contains("1440") || q.contains("2K") -> 1440
            q.contains("1080") || q.contains("FHD") -> 1080
            q.contains("720") || q.contains("HD") -> 720
            q.contains("480") || q.contains("SD") -> 480
            q.contains("360") -> 360
            else -> 720
        }
    }
}
