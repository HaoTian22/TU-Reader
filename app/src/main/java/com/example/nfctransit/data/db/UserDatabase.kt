package com.example.nfctransit.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 用户数据数据库（全新库，无迁移；旧数据由用户自行清除）。
 * 与映射库 AppDatabase（assets/transit.db，createFromAsset）完全分离。
 */
@Database(
    entities = [
        CardEntity::class,
        RawRecordEntity::class,
        ArchivedTransactionEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class UserDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao

    companion object {
        const val DB_NAME = "user_data.db"

        @Volatile
        private var instance: UserDatabase? = null

        fun get(context: Context): UserDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    UserDatabase::class.java,
                    DB_NAME
                ).build().also { instance = it }
            }
        }
    }
}
