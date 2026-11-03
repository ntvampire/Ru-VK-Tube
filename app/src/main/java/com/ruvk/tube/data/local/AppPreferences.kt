package com.ruvk.tube.data.local

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {

    private const val PREFS_NAME = "ruvk_tube_prefs"
    private const val KEY_PLAYER_TYPE = "pref_player_type"
    private const val KEY_TMDB_API_KEY = "pref_tmdb_api_key"
    private const val KEY_LAST_UPDATE_CHECK = "pref_last_update_check"

    const val PLAYER_INTERNAL = "internal"
    const val PLAYER_EXTERNAL = "external"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    var playerType: String
        get() = prefs.getString(KEY_PLAYER_TYPE, PLAYER_INTERNAL) ?: PLAYER_INTERNAL
        set(value) = prefs.edit().putString(KEY_PLAYER_TYPE, value).apply()

    val isExternalPlayer: Boolean
        get() = playerType == PLAYER_EXTERNAL

    var tmdbApiKey: String
        get() = prefs.getString(KEY_TMDB_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TMDB_API_KEY, value.trim()).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()
}
