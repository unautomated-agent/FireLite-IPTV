package dev.firetvlite.iptv.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

private const val CHANNEL_ROW =
    "SELECT c.*, EXISTS(SELECT 1 FROM favorite f WHERE f.streamUrl = c.streamUrl) AS isFavorite FROM channel c"

@Dao
interface SourceDao {
    @Query("SELECT * FROM source ORDER BY id LIMIT 1")
    fun observeFirst(): Flow<SourceEntity?>

    @Query("SELECT * FROM source ORDER BY id LIMIT 1")
    suspend fun first(): SourceEntity?

    @Insert
    suspend fun insert(source: SourceEntity): Long

    @Query("UPDATE source SET url = :url, userAgent = :userAgent WHERE id = :id")
    suspend fun updateConnection(id: Long, url: String, userAgent: String)

    @Query("UPDATE source SET refreshedAt = :refreshedAt WHERE id = :id")
    suspend fun markRefreshed(id: Long, refreshedAt: Long)
}

@Dao
interface ChannelDao {
    @Query(
        "SELECT groupName, COUNT(*) AS channelCount FROM channel " +
            "WHERE sourceId = :sourceId GROUP BY groupName ORDER BY MIN(position)",
    )
    fun observeGroups(sourceId: Long): Flow<List<GroupCount>>

    @Query("SELECT COUNT(*) FROM channel WHERE sourceId = :sourceId")
    fun observeCount(sourceId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM channel WHERE sourceId = :sourceId")
    suspend fun count(sourceId: Long): Int

    // ---- Lists shown on screen (paged, so 60,000 channels never sit in memory) ----

    @Query("$CHANNEL_ROW WHERE c.sourceId = :sourceId ORDER BY c.position")
    fun pagingAll(sourceId: Long): PagingSource<Int, ChannelRow>

    @Query("$CHANNEL_ROW WHERE c.sourceId = :sourceId AND c.groupName = :group ORDER BY c.position")
    fun pagingGroup(sourceId: Long, group: String): PagingSource<Int, ChannelRow>

    @Query(
        "$CHANNEL_ROW INNER JOIN favorite fav ON fav.streamUrl = c.streamUrl " +
            "WHERE c.sourceId = :sourceId ORDER BY fav.addedAt DESC",
    )
    fun pagingFavorites(sourceId: Long): PagingSource<Int, ChannelRow>

    @Query(
        "$CHANNEL_ROW INNER JOIN recent rec ON rec.streamUrl = c.streamUrl " +
            "WHERE c.sourceId = :sourceId ORDER BY rec.watchedAt DESC LIMIT 30",
    )
    fun pagingRecent(sourceId: Long): PagingSource<Int, ChannelRow>

    // ---- Neighbours for channel up/down while watching ----

    @Query("SELECT * FROM channel WHERE sourceId = :sourceId ORDER BY position LIMIT 1 OFFSET :offset")
    suspend fun allAt(sourceId: Long, offset: Int): ChannelEntity?

    @Query("SELECT COUNT(*) FROM channel WHERE sourceId = :sourceId AND groupName = :group")
    suspend fun groupCount(sourceId: Long, group: String): Int

    @Query(
        "SELECT * FROM channel WHERE sourceId = :sourceId AND groupName = :group " +
            "ORDER BY position LIMIT 1 OFFSET :offset",
    )
    suspend fun groupAt(sourceId: Long, group: String, offset: Int): ChannelEntity?

    @Query(
        "SELECT c.* FROM channel c INNER JOIN favorite fav ON fav.streamUrl = c.streamUrl " +
            "WHERE c.sourceId = :sourceId ORDER BY fav.addedAt DESC",
    )
    suspend fun favoriteChannels(sourceId: Long): List<ChannelEntity>

    @Query(
        "SELECT c.* FROM channel c INNER JOIN recent rec ON rec.streamUrl = c.streamUrl " +
            "WHERE c.sourceId = :sourceId ORDER BY rec.watchedAt DESC LIMIT 30",
    )
    suspend fun recentChannels(sourceId: Long): List<ChannelEntity>

    // ---- Import ----

    @Insert
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channel WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}

@Dao
interface UserDao {
    @Query("SELECT COUNT(*) FROM favorite")
    fun observeFavoriteCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM recent")
    fun observeRecentCount(): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE streamUrl = :streamUrl)")
    fun observeIsFavorite(streamUrl: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE streamUrl = :streamUrl)")
    suspend fun isFavorite(streamUrl: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE streamUrl = :streamUrl")
    suspend fun removeFavorite(streamUrl: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markWatched(recent: RecentEntity)

    /** Keeps the history short. */
    @Query(
        "DELETE FROM recent WHERE streamUrl NOT IN " +
            "(SELECT streamUrl FROM recent ORDER BY watchedAt DESC LIMIT 50)",
    )
    suspend fun trimRecent()
}
