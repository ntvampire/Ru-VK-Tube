package com.ruvk.tube.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
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
    val season: Int = 0,
    val episode: Int = 0,
    val lastStreamUrl: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val tmdbId: Long,
    val title: String,
    val posterUrl: String?,
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
    entities = [HistoryEntity::class, FavoriteEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun favoritesDao(): FavoritesDao
}
