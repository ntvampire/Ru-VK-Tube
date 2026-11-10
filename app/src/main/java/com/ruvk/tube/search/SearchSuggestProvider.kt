package com.ruvk.tube.search

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import com.ruvk.tube.RuVkTubeApp
import com.ruvk.tube.data.api.tmdb.TmdbRepository
import com.ruvk.tube.domain.models.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Провайдер поисковых подсказок для Android TV Global Search и ATV-RU Поиска (TVVoiceSearch).
 *
 * Предоставляет Cursor со стандартными колонками Android TV и расширенными метаданными TMDB.
 * URI: content://com.ruvk.tube.search/search_suggest_query/<query>?limit=10
 */
class SearchSuggestProvider : ContentProvider() {

    companion object {
        private const val TAG = "SearchSuggestProvider"
        private const val SEARCH_SUGGEST = 1
        private const val SEARCH_SHORTCUT = 2

        val COLUMNS = arrayOf(
            "_id",
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            "suggest_icon_1",
            "suggest_result_card_image",
            "suggest_production_year",
            "suggest_rating_score",
            "suggest_rating_style",
            "suggest_duration",
            "suggest_intent_data_id",
            "suggest_intent_extra_data"
        )
    }

    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH)

    override fun onCreate(): Boolean {
        val authority = context?.packageName?.let { "$it.search" } ?: "com.ruvk.tube.search"
        uriMatcher.addURI(authority, "search_suggest_query/*", SEARCH_SUGGEST)
        uriMatcher.addURI(authority, "search_suggest_query", SEARCH_SUGGEST)
        uriMatcher.addURI(authority, SearchManager.SUGGEST_URI_PATH_QUERY + "/*", SEARCH_SUGGEST)
        uriMatcher.addURI(authority, SearchManager.SUGGEST_URI_PATH_QUERY, SEARCH_SUGGEST)
        uriMatcher.addURI(authority, SearchManager.SUGGEST_URI_PATH_SHORTCUT + "/*", SEARCH_SHORTCUT)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val query = extractQuery(uri, selectionArgs)
        val cursor = MatrixCursor(COLUMNS)

        if (query.isNullOrBlank()) {
            return cursor
        }

        try {
            val repository = getTmdbRepository()
            val items = runBlocking {
                withTimeoutOrNull(2000L) {
                    withContext(Dispatchers.IO) {
                        repository.search(query.trim())
                    }
                }
            } ?: emptyList()

            for (item in items.take(15)) {
                val mediaTypeParam = if (item.mediaType == MediaType.TV) "tv" else "movie"
                val deepLink = "ruvk://details?tmdbId=${item.id}&mediaType=$mediaTypeParam"
                val yearStr = if (item.year > 0) item.year.toString() else ""
                val typeRu = if (item.mediaType == MediaType.TV) "Сериал" else "Фильм"
                val subtitle = listOfNotNull(
                    typeRu,
                    yearStr.takeIf { it.isNotBlank() },
                    item.country?.takeIf { it.isNotBlank() },
                    item.ageRating?.takeIf { it.isNotBlank() }
                ).joinToString(" • ")

                val durationMs = if (item.runtimeMinutes > 0) item.runtimeMinutes * 60_000L else 0L
                val ratingScore = if (item.voteAverage > 0.0) {
                    String.format(Locale.US, "%.1f", item.voteAverage)
                } else {
                    ""
                }

                cursor.addRow(
                    arrayOf(
                        item.id,                               // _id
                        item.title,                            // SUGGEST_COLUMN_TEXT_1
                        subtitle,                              // SUGGEST_COLUMN_TEXT_2
                        deepLink,                              // SUGGEST_COLUMN_INTENT_DATA
                        item.posterUrl ?: "",                  // suggest_icon_1 (вертикальный постер)
                        item.backdropUrl ?: "",                // suggest_result_card_image (16:9)
                        item.year,                             // suggest_production_year
                        ratingScore,                           // suggest_rating_score
                        "-1",                                  // suggest_rating_style (-1: 0-10)
                        durationMs,                            // suggest_duration (мс)
                        item.id.toString(),                    // suggest_intent_data_id (TMDB ID для ATV-RU)
                        mediaTypeParam                         // suggest_intent_extra_data ("movie" | "tv")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing search suggestion for '$query'", e)
        }

        return cursor
    }

    private fun extractQuery(uri: Uri, selectionArgs: Array<out String>?): String? {
        val lastSegment = uri.lastPathSegment
        if (!lastSegment.isNullOrBlank() && lastSegment != "search_suggest_query" && lastSegment != SearchManager.SUGGEST_URI_PATH_QUERY) {
            return lastSegment
        }
        if (!selectionArgs.isNullOrEmpty() && !selectionArgs[0].isNullOrBlank()) {
            return selectionArgs[0]
        }
        return uri.getQueryParameter("query")
            ?: uri.getQueryParameter("q")
            ?: uri.getQueryParameter("search_query")
    }

    private fun getTmdbRepository(): TmdbRepository {
        return try {
            RuVkTubeApp.instance.tmdbRepository
        } catch (_: Exception) {
            TmdbRepository()
        }
    }

    override fun getType(uri: Uri): String {
        return SearchManager.SUGGEST_MIME_TYPE
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
