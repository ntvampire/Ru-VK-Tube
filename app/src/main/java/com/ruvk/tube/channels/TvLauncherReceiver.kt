package com.ruvk.tube.channels

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TvLauncherReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "android.media.tv.action.INITIALIZE_PROGRAMS") {
            Log.d("TvLauncherReceiver", "Received INITIALIZE_PROGRAMS, starting TV channels sync")
            val pendingResult = goAsync()
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    TvChannelsManager.syncAllChannels(appContext)
                } catch (e: Exception) {
                    Log.e("TvLauncherReceiver", "Failed to sync TV channels from broadcast", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
