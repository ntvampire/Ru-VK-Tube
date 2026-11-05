package com.ruvk.tube.channels

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class TvChannelsWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            TvChannelsManager.syncAllChannels(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e("TvChannelsWorker", "Error executing TV channels periodic sync", e)
            Result.retry()
        }
    }
}
