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
    ],
    version = 2,
    exportSchema = false,
)
abstract class FireLiteDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun channelDao(): ChannelDao
    abstract fun userDao(): UserDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE source ADD COLUMN lastUsedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE source SET lastUsedAt = 1 WHERE id = (SELECT MIN(id) FROM source)")
            }
        }

        fun create(context: Context): FireLiteDatabase =
            Room.databaseBuilder(context, FireLiteDatabase::class.java, "firelite.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
