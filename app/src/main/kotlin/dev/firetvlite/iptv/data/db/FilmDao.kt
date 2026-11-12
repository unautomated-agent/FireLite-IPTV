package dev.firetvlite.iptv.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.firetvlite.iptv.data.film.Films
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "film_rating")
data class FilmRatingEntity(@PrimaryKey val cacheKey: String, val imdbRating: Double?,
    val imdbVotes: Long, val imdbId: String?, val fetchedAt: Long)

@Entity(tableName = "film_budget")
data class FilmBudgetEntity(@PrimaryKey val day: Long, val used: Int = 0)

@Dao
interface FilmDao {
    @Query("SELECT * FROM film_rating WHERE cacheKey=:key")
    suspend fun cached(key: String): FilmRatingEntity?
    @Query("SELECT * FROM film_rating")
    fun observeRatings(): Flow<List<FilmRatingEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(rating: FilmRatingEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun budget(budget: FilmBudgetEntity)
    @Query("UPDATE film_budget SET used=used+1 WHERE day=:day AND used<${Films.DAILY_BUDGET}")
    suspend fun reserve(day: Long): Int
}
