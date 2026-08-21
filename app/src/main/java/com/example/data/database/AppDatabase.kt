package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [MediaEntity::class, HistoryEntity::class, PreferenceEntity::class],
    version = 25,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun historyDao(): HistoryDao
    abstract fun preferenceDao(): PreferenceDao

    companion object {
        private const val DB_NAME = "aero_player_main.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext
                // Clean up any legacy corrupted databases from prior schemas
                try {
                    appContext.deleteDatabase("vlc_player_ai_db")
                } catch (e: Throwable) {
                    e.printStackTrace()
                }

                try {
                    val instance = Room.databaseBuilder(
                        appContext,
                        AppDatabase::class.java,
                        DB_NAME
                    )
                    .fallbackToDestructiveMigration()
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    INSTANCE = instance
                    instance
                } catch (e: Throwable) {
                    android.util.Log.e("AppDatabase", "Failed to build Room database, recreating: ${e.message}", e)
                    try {
                        appContext.deleteDatabase(DB_NAME)
                    } catch (delEx: Throwable) {
                        delEx.printStackTrace()
                    }
                    val freshInstance = Room.databaseBuilder(
                        appContext,
                        AppDatabase::class.java,
                        DB_NAME
                    )
                    .fallbackToDestructiveMigration()
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    INSTANCE = freshInstance
                    freshInstance
                }
            }
        }
    }
}
