package com.ruvk.tube

import android.app.Application
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.ruvk.tube.data.api.rutube.RutubeClient
import com.ruvk.tube.data.api.tmdb.TmdbRepository
import com.ruvk.tube.data.api.vk.VkClient
import com.ruvk.tube.data.db.AppDatabase
import com.ruvk.tube.data.local.AppPreferences
import com.ruvk.tube.data.network.NetworkClient
import com.ruvk.tube.domain.search.SearchCoordinator

class RuVkTubeApp : Application(), ImageLoaderFactory {

    lateinit var database: AppDatabase
        private set

    lateinit var tmdbRepository: TmdbRepository
        private set

    lateinit var searchCoordinator: SearchCoordinator
        private set

    lateinit var vkClient: VkClient
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        AppPreferences.init(this)

        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "ruvk_tube.db"
        )
        .addMigrations(AppDatabase.MIGRATION_2_3)
        .fallbackToDestructiveMigration()
        .build()

        tmdbRepository = TmdbRepository()
        vkClient = VkClient()
        searchCoordinator = SearchCoordinator(
            rutubeClient = RutubeClient(),
            vkClient = vkClient
        )
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(NetworkClient.okHttpClient)
            .crossfade(true)
            .build()
    }

    companion object {
        lateinit var instance: RuVkTubeApp
            private set
    }
}
