package dev.firetvlite.iptv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import dev.firetvlite.iptv.data.film.ReminderShowing

private const val MAPPED_PROGRAMMES =
    "FROM channel c JOIN source s ON s.id = c.sourceId " +
        "JOIN channel_epg m ON m.sourceId = c.sourceId AND m.streamUrl = c.streamUrl AND s.epgGeneration = m.generation " +
        "JOIN programme p ON p.sourceId = m.sourceId AND p.generation = m.generation AND p.epgKey = m.epgKey "
private const val PROGRAMME_AT =
    "SELECT p.* $MAPPED_PROGRAMMES WHERE c.id = :channelId AND p.start <= :time AND p.stop > :time " +
        "ORDER BY p.start DESC LIMIT 1"

@Dao
interface EpgDao {
    @Query("SELECT c.id AS channelId, c.name AS channelName, p.start, p.stop, p.title, p.originalTitle " +
        "$MAPPED_PROGRAMMES WHERE c.sourceId=:sourceId AND p.start < :until AND p.stop > :now ORDER BY p.start, c.id")
    suspend fun reminderWindow(sourceId: Long, now: Long, until: Long): List<ReminderShowing>

    @Query("SELECT * FROM channel WHERE sourceId=:sourceId ORDER BY position")
    fun filmChannels(sourceId: Long): Flow<List<ChannelEntity>>
    @Query("SELECT c.id AS channelId, p.id, p.sourceId, p.generation, p.epgKey, p.start, p.stop, p.title, " +
        "NULL AS description, p.category, p.year, p.originalTitle, NULL AS credits, p.episodeNum, p.categories " +
        "$MAPPED_PROGRAMMES WHERE c.sourceId=:sourceId AND p.stop - p.start >= 4800000 " +
        "AND p.start < :until AND p.stop > :from ORDER BY p.start, c.id")
    fun filmWindow(sourceId: Long, from: Long, until: Long): Flow<List<ChannelProgramme>>
    @Query("SELECT COUNT(*) FROM channel c JOIN source s ON s.id=c.sourceId " +
        "JOIN channel_epg m ON m.sourceId=c.sourceId AND m.streamUrl=c.streamUrl AND m.generation=s.epgGeneration " +
        "WHERE c.sourceId=:sourceId AND EXISTS(SELECT 1 FROM programme p WHERE p.sourceId=m.sourceId " +
        "AND p.generation=m.generation AND p.epgKey=m.epgKey)")
    fun observeMatchedCount(sourceId: Long): Flow<Int>

    @Query("SELECT c.id FROM channel c JOIN source s ON s.id = c.sourceId " +
        "JOIN channel_epg m ON m.sourceId = c.sourceId AND m.streamUrl = c.streamUrl AND m.generation = s.epgGeneration " +
        "WHERE c.id IN (:channelIds) AND EXISTS(SELECT 1 FROM programme p WHERE p.sourceId = m.sourceId " +
        "AND p.generation = m.generation AND p.epgKey = m.epgKey)")
    fun observeGuideChannels(channelIds: List<Long>): Flow<List<Long>>

    @Query("SELECT MAX(p.stop) $MAPPED_PROGRAMMES WHERE c.sourceId=:sourceId " +
        "AND (:groupName IS NULL OR c.groupName=:groupName) " +
        "AND (:favorites=0 OR EXISTS(SELECT 1 FROM favorite f WHERE f.streamUrl=c.streamUrl)) " +
        "AND (:recent=0 OR EXISTS(SELECT 1 FROM recent r WHERE r.streamUrl=c.streamUrl))")
    fun guideEnd(sourceId: Long, groupName: String?, favorites: Boolean, recent: Boolean): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProgrammes(programmes: List<ProgrammeEntity>): List<Long>

    @Insert
    suspend fun insertMappings(mappings: List<ChannelEpgEntity>)

    @Query("DELETE FROM programme WHERE sourceId = :sourceId")
    suspend fun clearProgrammes(sourceId: Long)

    @Query("DELETE FROM channel_epg WHERE sourceId = :sourceId")
    suspend fun clearMappings(sourceId: Long)

    @Query("SELECT id, name, tvgId, streamUrl, tvgName FROM channel WHERE sourceId = :sourceId ORDER BY position")
    suspend fun channels(sourceId: Long): List<EpgChannel>

    @Query("SELECT COALESCE(MAX(catchupDays), 0) FROM channel WHERE sourceId = :sourceId")
    suspend fun maxCatchupDays(sourceId: Long): Int

    @Query("DELETE FROM programme WHERE sourceId = :sourceId AND generation != :generation")
    suspend fun deleteOtherProgrammes(sourceId: Long, generation: String)

    @Query("DELETE FROM channel_epg WHERE sourceId = :sourceId AND generation != :generation")
    suspend fun deleteOtherMappings(sourceId: Long, generation: String)

    @Query("DELETE FROM programme WHERE sourceId = :sourceId AND generation = :generation")
    suspend fun deleteProgrammes(sourceId: Long, generation: String)

    @Query("DELETE FROM channel_epg WHERE sourceId = :sourceId AND generation = :generation")
    suspend fun deleteMappings(sourceId: Long, generation: String)

    @Query(PROGRAMME_AT)
    suspend fun programmeAt(channelId: Long, time: Long): ProgrammeEntity?

    @Query(PROGRAMME_AT)
    fun observeAt(channelId: Long, time: Long): Flow<ProgrammeEntity?>

    @Query("SELECT p.* $MAPPED_PROGRAMMES WHERE c.id = :channelId AND p.start > :time " +
        "ORDER BY p.start LIMIT 1")
    fun observeNext(channelId: Long, time: Long): Flow<ProgrammeEntity?>

    @Query("SELECT c.id AS channelId, p.* $MAPPED_PROGRAMMES WHERE c.id IN (:channelIds) " +
        "AND p.start < :until AND p.stop > :from ORDER BY c.id, p.start")
    fun observeWindow(channelIds: List<Long>, from: Long, until: Long): Flow<List<ChannelProgramme>>
}
