package com.ruvk.tube.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "watch_history")
data class HistoryEntity(
    @PrimaryKey val tmdbId: Long,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val mediaType: String,
    val positionMs: Long,
    val durationMs: Long,
    val year: Int = 0,
    val rating: Double = 0.0,
    val overview: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
    val lastStreamUrl: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "episode_progress",
    primaryKeys = ["tmdbId", "season", "episode"]
)
data class EpisodeProgressEntity(
    val tmdbId: Long,
    val season: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val lastStreamUrl: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val tmdbId: Long,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val mediaType: String,
    val year: Int,
    val rating: Double,
    val addedAt: Long = System.currentTimeMillis()
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY updatedAt DESC LIMIT 20")
    fun getAllHistory(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE tmdbId = :tmdbId LIMIT 1")
    suspend fun getByTmdbId(tmdbId: Long): HistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: HistoryEntity)

    @Query("DELETE FROM watch_history WHERE tmdbId = :tmdbId")
    suspend fun delete(tmdbId: Long)
}

@Dao
interface EpisodeProgressDao {
    @Query("SELECT * FROM episode_progress WHERE tmdbId = :tmdbId AND season = :season AND episode = :episode LIMIT 1")
    suspend fun getProgress(tmdbId: Long, season: Int, episode: Int): EpisodeProgressEntity?

    @Query("SELECT * FROM episode_progress WHERE tmdbId = :tmdbId ORDER BY updatedAt DESC")
    suspend fun getAllForShow(tmdbId: Long): List<EpisodeProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(entity: EpisodeProgressEntity)

    @Query("DELETE FROM episode_progress WHERE tmdbId = :tmdbId")
    suspend fun deleteForShow(tmdbId: Long)
}

@Dao
interface FavoritesDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE tmdbId = :tmdbId)")
    suspend fun isFavorite(tmdbId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE tmdbId = :tmdbId")
    suspend fun delete(tmdbId: Long)
}

@Database(
    entities = [HistoryEntity::class, FavoriteEntity::class, EpisodeProgressEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun episodeProgressDao(): EpisodeProgressDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `episode_progress` (
                        `tmdbId` INTEGER NOT NULL,
                        `season` INTEGER NOT NULL,
                        `episode` INTEGER NOT NULL,
                        `positionMs` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `lastStreamUrl` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`tmdbId`, `season`, `episode`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
