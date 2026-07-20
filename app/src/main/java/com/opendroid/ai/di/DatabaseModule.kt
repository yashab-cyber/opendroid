package com.opendroid.ai.di

import android.content.Context
import androidx.room.Room
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.db.dao.ConversationDao
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.dao.MemoryDao
import com.opendroid.ai.data.db.dao.PlanDao
import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.db.dao.TaskHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

import com.opendroid.ai.data.db.dao.ModelDao
import com.opendroid.ai.data.db.dao.UnknownActionDao

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OpenDroidDatabase {
        return Room.databaseBuilder(
            context,
            OpenDroidDatabase::class.java,
            "opendroid_database"
        )
        .addMigrations(
            OpenDroidDatabase.MIGRATION_1_2,
            OpenDroidDatabase.MIGRATION_2_3,
            OpenDroidDatabase.MIGRATION_3_4,
            OpenDroidDatabase.MIGRATION_4_5
        )
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    @Singleton
    fun provideConversationDao(db: OpenDroidDatabase): ConversationDao = db.conversationDao()

    @Provides
    @Singleton
    fun providePlanDao(db: OpenDroidDatabase): PlanDao = db.planDao()

    @Provides
    @Singleton
    fun provideMemoryDao(db: OpenDroidDatabase): MemoryDao = db.memoryDao()

    @Provides
    @Singleton
    fun provideTaskHistoryDao(db: OpenDroidDatabase): TaskHistoryDao = db.taskHistoryDao()

    @Provides
    @Singleton
    fun provideMacroDao(db: OpenDroidDatabase): MacroDao = db.macroDao()

    @Provides
    @Singleton
    fun provideUnknownActionDao(db: OpenDroidDatabase): UnknownActionDao = db.unknownActionDao()

    @Provides
    @Singleton
    fun provideNotificationDao(db: OpenDroidDatabase): NotificationDao = db.notificationDao()

    @Provides
    @Singleton
    fun provideModelDao(db: OpenDroidDatabase): ModelDao = db.modelDao()
}
