package com.itvs.connect.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        RideEntity::class,
        ParkedLocationEntity::class,
        SavedPlaceEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
    abstract fun parkedLocationDao(): ParkedLocationDao
    abstract fun savedPlaceDao(): SavedPlaceDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "itvs_connect.db"
                ).build().also { instance = it }
            }
    }
}
