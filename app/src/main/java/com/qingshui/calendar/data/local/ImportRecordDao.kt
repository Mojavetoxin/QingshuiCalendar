package com.qingshui.calendar.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.qingshui.calendar.data.local.entity.ImportRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportRecordDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: ImportRecordEntity): Long

    @Update
    suspend fun update(record: ImportRecordEntity)

    @Query("SELECT * FROM import_records WHERE sourceUri = :uri AND lineHash = :hash LIMIT 1")
    suspend fun findByHash(uri: String, hash: String): ImportRecordEntity?

    @Query("SELECT COUNT(*) FROM import_records WHERE sourceUri = :uri AND lineHash = :hash")
    suspend fun countByHash(uri: String, hash: String): Int

    @Query("SELECT * FROM import_records WHERE status = 1 ORDER BY processedAt DESC")
    fun observePending(): Flow<List<ImportRecordEntity>>

    @Query("DELETE FROM import_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM import_records")
    suspend fun deleteAll()
}
