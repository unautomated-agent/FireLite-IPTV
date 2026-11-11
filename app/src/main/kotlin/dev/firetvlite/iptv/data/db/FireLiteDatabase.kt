package dev.firetvlite.iptv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SourceEntity::class,
        ChannelEntity::class,
        FavoriteEntity::class,
        RecentEntity::class,
        ProgrammeEntity::class,
        ChannelEpgEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class FireLiteDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun channelDao(): ChannelDao
    abstract fun userDao(): UserDao
    abstract fun epgDao(): EpgDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE source ADD COLUMN lastUsedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE source SET lastUsedAt = 1 WHERE id = (SELECT MIN(id) FROM source)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE source ADD COLUMN epgUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE source ADD COLUMN discoveredEpgUrls TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE source ADD COLUMN epgRefreshedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE source ADD COLUMN epgProgrammeCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE source ADD COLUMN epgGeneration TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS programme (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "sourceId INTEGER NOT NULL, generation TEXT NOT NULL, epgKey TEXT NOT NULL, " +
                        "start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL, " +
                        "description TEXT, category TEXT, " +
                        "FOREIGN KEY(sourceId) REFERENCES source(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_programme_sourceId_generation_epgKey_start_stop " +
                    "ON programme(sourceId, generation, epgKey, start, stop)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS channel_epg (sourceId INTEGER NOT NULL, generation TEXT NOT NULL, " +
                        "streamUrl TEXT NOT NULL, epgKey TEXT NOT NULL, PRIMARY KEY(sourceId, generation, streamUrl), " +
                        "FOREIGN KEY(sourceId) REFERENCES source(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
        }

        fun create(context: Context): FireLiteDatabase =
            Room.databaseBuilder(context, FireLiteDatabase::class.java, "firelite.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
