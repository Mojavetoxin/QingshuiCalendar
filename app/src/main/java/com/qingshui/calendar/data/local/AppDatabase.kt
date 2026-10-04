package com.qingshui.calendar.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.qingshui.calendar.data.local.entity.EventEntity
import com.qingshui.calendar.data.local.entity.ImportRecordEntity

@Database(
    entities = [EventEntity::class, ImportRecordEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun importRecordDao(): ImportRecordDao
}
