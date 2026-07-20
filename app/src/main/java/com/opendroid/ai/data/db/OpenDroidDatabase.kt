package com.opendroid.ai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.opendroid.ai.data.db.dao.ConversationDao
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.dao.MemoryDao
import com.opendroid.ai.data.db.dao.PlanDao
import com.opendroid.ai.data.db.dao.TaskHistoryDao
import com.opendroid.ai.data.db.entities.ConversationEntity
import com.opendroid.ai.data.db.entities.MacroEntity
import com.opendroid.ai.data.db.entities.MemoryEntity
import com.opendroid.ai.data.db.entities.PlanEntity
import com.opendroid.ai.data.db.entities.TaskHistoryEntity

import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.db.dao.UnknownActionDao
import com.opendroid.ai.data.db.dao.ModelDao
import com.opendroid.ai.data.db.entities.NotificationEntity
import com.opendroid.ai.data.db.entities.UnknownActionEntity
import com.opendroid.ai.data.db.entities.ModelEntity
import androidx.room.TypeConverters

@Database(
    entities = [
        ConversationEntity::class,
        PlanEntity::class,
        MemoryEntity::class,
        TaskHistoryEntity::class,
        MacroEntity::class,
        UnknownActionEntity::class,
        NotificationEntity::class,
        ModelEntity::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class OpenDroidDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun planDao(): PlanDao
    abstract fun memoryDao(): MemoryDao
    abstract fun taskHistoryDao(): TaskHistoryDao
    abstract fun macroDao(): MacroDao
    abstract fun unknownActionDao(): UnknownActionDao
    abstract fun notificationDao(): NotificationDao
    abstract fun modelDao(): ModelDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE conversations ADD COLUMN contactPickerData TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS notifications (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        packageName TEXT NOT NULL,
                        appName TEXT NOT NULL,
                        title TEXT NOT NULL,
                        text TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        category TEXT NOT NULL DEFAULT 'OTHER',
                        isAutoReplied INTEGER NOT NULL DEFAULT 0,
                        autoReplyText TEXT,
                        contactName TEXT,
                        isRead INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notifications ADD COLUMN senderEmail TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS models (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        version TEXT NOT NULL,
                        size INTEGER NOT NULL,
                        downloadUrl TEXT NOT NULL,
                        localPath TEXT NOT NULL,
                        status TEXT NOT NULL,
                        downloadProgress INTEGER NOT NULL,
                        lastUsed INTEGER NOT NULL,
                        installedAt INTEGER NOT NULL,
                        downloadedSize INTEGER NOT NULL DEFAULT 0,
                        downloadSpeed TEXT NOT NULL DEFAULT '',
                        etaString TEXT NOT NULL DEFAULT ''
                    )
                """)
            }
        }
    }
}
