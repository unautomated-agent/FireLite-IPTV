package dev.firetvlite.iptv.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.firetvlite.iptv.data.library.SourcePolicy
import kotlinx.coroutines.flow.Flow

private const val CHANNEL_ROW =
    "SELECT c.*, EXISTS(SELECT 1 FROM favorite f WHERE f.streamUrl = c.streamUrl) AS isFavorite FROM channel c"

data class ArchiveTypeCount(val type: String, val count: Int)

@Dao
interface SourceDao {
    @Query("SELECT * FROM source ORDER BY lastUsedAt DESC, id DESC LIMIT 1")
    fun observeActive(): Flow<SourceEntity?>

    @Query("SELECT * FROM source ORDER BY lastUsedAt DESC, id DESC LIMIT 1")
    suspend fun active(): SourceEntity?

    @Query("SELECT source.*, (SELECT COUNT(*) FROM channel WHERE sourceId = source.id) AS channelCount " +
        "FROM source ORDER BY lastUsedAt DESC, id DESC")
    fun observeAll(): Flow<List<SavedSource>>

    @Query("SELECT * FROM source WHERE id = :id")
    suspend fun byId(id: Long): SourceEntity?

    @Query("SELECT COALESCE(MAX(lastUsedAt), 0) FROM source")
    suspend fun mostRecentUse(): Long

    @Query("UPDATE source SET lastUsedAt = :usedAt WHERE id = :id")
    suspend fun markUsed(id: Long, usedAt: Long)

    @Transaction
    suspend fun activate(id: Long, now: Long) {
        markUsed(id, SourcePolicy.nextUsedAt(mostRecentUse(), now))
    }

    @Query("DELETE FROM source WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insert(source: SourceEntity): Long

    @Query("UPDATE source SET url = :url, userAgent = :userAgent, epgUrl = :epgUrl WHERE id = :id")
    suspend fun updateConnection(id: Long, url: String, userAgent: String, epgUrl: String)

    @Query("UPDATE source SET discoveredEpgUrls = :urls WHERE id = :id")
    suspend fun updateDiscoveredEpg(id: Long, urls: String)

    @Query("UPDATE source SET epgGeneration = :generation, epgRefreshedAt = :time, epgProgrammeCount = :count " +
        "WHERE id = :id")
    suspend fun publishEpg(id: Long, generation: String, time: Long, count: Int)

    @Query("UPDATE source SET refreshedAt=:refreshedAt, importedVersionCode=:version, importedParserRevision=:revision, " +
        "rawCatchupChannels=:rawCatchup, playlistUserAgent=:userAgent, playlistDeviceType=:deviceType WHERE id=:id")
    suspend fun markImported(id: Long, refreshedAt: Long, version: Int, revision: Int, rawCatchup: Int,
        userAgent: String, deviceType: String)
}

@Dao
interface ChannelDao {
    @Query("SELECT id, streamUrl FROM channel WHERE sourceId=:sourceId ORDER BY position")
    suspend fun identities(sourceId: Long): List<ChannelIdentity>

    @Query("SELECT streamUrl FROM channel WHERE sourceId=:sourceId ORDER BY position LIMIT 1")
    fun observeDiagnosticSample(sourceId: Long): Flow<String?>

    @Query("SELECT type, COUNT(*) AS count FROM (SELECT LOWER(TRIM(COALESCE(catchupType, " +
        "CASE WHEN LENGTH(TRIM(COALESCE(catchupSource,'')))>0 THEN 'default' ELSE '' END))) AS type, catchupSource " +
        "FROM channel WHERE sourceId=:sourceId AND catchupDays>0) " +
        "WHERE type IN ('shift','timeshift','xc','flussonic','fs') OR " +
        "(type IN ('default','append') AND LENGTH(TRIM(COALESCE(catchupSource,'')))>0) GROUP BY type")
    fun observeArchiveTypes(sourceId: Long): Flow<List<ArchiveTypeCount>>

    @Query("SELECT COALESCE(MAX(c.catchupDays), 0) FROM channel c WHERE c.sourceId=:sourceId " +
        "AND (:groupName IS NULL OR c.groupName=:groupName) " +
        "AND (:favorites=0 OR EXISTS(SELECT 1 FROM favorite f WHERE f.streamUrl=c.streamUrl)) " +
        "AND (:recent=0 OR EXISTS(SELECT 1 FROM recent r WHERE r.streamUrl=c.streamUrl))")
    fun guideCatchupDays(sourceId: Long, groupName: String?, favorites: Boolean, recent: Boolean): Flow<Int>

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

    @Query("SELECT (SELECT COUNT(*) FROM channel x WHERE x.sourceId=c.sourceId AND x.position<c.position) " +
        "FROM channel c WHERE c.sourceId=:sourceId AND c.streamUrl=:url ORDER BY c.position LIMIT 1")
    suspend fun allIndex(sourceId: Long, url: String): Int?

    @Query("SELECT (SELECT COUNT(*) FROM channel x WHERE x.sourceId=c.sourceId AND x.position<c.position) " +
        "FROM channel c WHERE c.sourceId=:sourceId AND c.id=:channelId")
    suspend fun allIndexById(sourceId: Long, channelId: Long): Int?

    @Query("SELECT (SELECT COUNT(*) FROM channel x WHERE x.sourceId=c.sourceId AND x.groupName=c.groupName AND x.position<c.position) " +
        "FROM channel c WHERE c.sourceId=:sourceId AND c.groupName=:group AND c.streamUrl=:url ORDER BY c.position LIMIT 1")
    suspend fun groupIndex(sourceId: Long, group: String, url: String): Int?

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

    @Query("SELECT c.* FROM channel c INNER JOIN recent r ON r.streamUrl = c.streamUrl " +
        "WHERE c.sourceId = :sourceId AND c.id = (SELECT x.id FROM channel x " +
        "WHERE x.sourceId = c.sourceId AND x.streamUrl = c.streamUrl ORDER BY x.position, x.id LIMIT 1) " +
        "ORDER BY r.watchedAt DESC, c.position, c.id LIMIT 2")
    fun startupRecent(sourceId: Long): Flow<List<ChannelEntity>>

    // ---- Import ----

    @Insert
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channel WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}

@Dao
interface UserDao {
    @Query("SELECT COUNT(*) FROM channel c INNER JOIN favorite f ON f.streamUrl = c.streamUrl " +
        "WHERE c.sourceId = :sourceId")
    fun observeFavoriteCount(sourceId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM (SELECT c.id FROM channel c INNER JOIN recent r ON r.streamUrl = c.streamUrl " +
        "WHERE c.sourceId = :sourceId ORDER BY r.watchedAt DESC LIMIT 30)")
    fun observeRecentCount(sourceId: Long): Flow<Int>

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
