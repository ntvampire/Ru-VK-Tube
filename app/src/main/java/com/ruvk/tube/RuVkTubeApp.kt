package com.ruvk.tube

import android.app.Application
import androidx.room.Room
import com.ruvk.tube.data.api.rutube.RutubeClient
import com.ruvk.tube.data.api.tmdb.TmdbRepository
import com.ruvk.tube.data.api.vk.VkClient
import com.ruvk.tube.data.db.AppDatabase
import com.ruvk.tube.domain.search.SearchCoordinator

class RuVkTubeApp : Application() {

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

        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "ruvk_tube.db"
        ).fallbackToDestructiveMigration().build()

        tmdbRepository = TmdbRepository()
        vkClient = VkClient()
        searchCoordinator = SearchCoordinator(
            rutubeClient = RutubeClient(),
            vkClient = vkClient
        )
    }

    companion object {
        lateinit var instance: RuVkTubeApp
            private set
    }
}
