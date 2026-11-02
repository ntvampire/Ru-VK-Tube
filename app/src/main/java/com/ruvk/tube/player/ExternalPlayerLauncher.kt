package com.ruvk.tube.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object ExternalPlayerLauncher {

    fun launch(context: Context, streamUrl: String, title: String, positionMs: Long = 0) {
        val uri = Uri.parse(streamUrl)
        val mimeType = if (streamUrl.contains(".m3u8")) "application/x-mpegURL" else "video/*"

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            putExtra("title", title)
            putExtra("position", positionMs.toInt())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(Intent.createChooser(intent, "Выберите видеоплеер"))
        } catch (e: Exception) {
            Toast.makeText(context, "Подходящий внешний плеер не установлен", Toast.LENGTH_LONG).show()
        }
    }
}
