package dev.firetvlite.iptv.data.db

import androidx.room.Embedded
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Generations keep a partially downloaded guide invisible until it is published. */
@Entity(
    tableName = "programme",
    foreignKeys = [ForeignKey(
        entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["sourceId", "generation", "epgKey", "start", "stop"], unique = true)],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val generation: String,
    val epgKey: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
    val category: String?,
    val year: Int? = null,
    val originalTitle: String? = null,
    val credits: String? = null,
    val episodeNum: String? = null,
    @ColumnInfo(defaultValue = "''") val categories: String = "",
)

@Entity(
    tableName = "channel_epg",
    primaryKeys = ["sourceId", "generation", "streamUrl"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChannelEpgEntity(
    val sourceId: Long,
    val generation: String,
    val streamUrl: String,
    val epgKey: String,
)

data class EpgChannel(
    val id: Long,
    val name: String,
    val tvgId: String?,
    val streamUrl: String,
    val tvgName: String? = null,
)

data class ChannelProgramme(
    val channelId: Long,
    @Embedded val programme: ProgrammeEntity,
)
