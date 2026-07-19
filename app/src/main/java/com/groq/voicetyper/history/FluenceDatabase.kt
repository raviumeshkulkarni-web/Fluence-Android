package com.groq.voicetyper.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TranscriptionEntry::class], version = 1, exportSchema = false)
abstract class FluenceDatabase : RoomDatabase() {
    abstract fun transcriptionHistoryDao(): TranscriptionHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: FluenceDatabase? = null

        fun getInstance(context: Context): FluenceDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FluenceDatabase::class.java,
                    "fluence_database"
                )
                    .allowMainThreadQueries()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}