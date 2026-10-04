package com.qingshui.calendar.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.qingshui.calendar.data.local.entity.EventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: EventEntity): Long

    @Update
    suspend fun update(event: EventEntity)

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: Long): EventEntity?

    @Query("SELECT * FROM events")
    suspend fun getAll(): List<EventEntity>

    @Query("SELECT * FROM events ORDER BY startTime ASC")
    fun observeAll(): Flow<List<EventEntity>>

    /**
     * 查询与区间有交集的日程：
     * 普通日程按 [startTime, endTime) 与区间重叠；
     * 重复日程只要规则起点 <= 区间末 且 未在区间前截止 即纳入（再由 RepeatExpander 展开日期）。
     */
    @Query(
        """SELECT * FROM events WHERE
           (startTime < :rangeEnd AND endTime > :rangeStart)
           OR (repeatType != 'NONE' AND startTime <= :rangeEnd
               AND (repeatEndTime IS NULL OR repeatEndTime >= :rangeStart))"""
    )
    fun observeInRange(rangeStart: Long, rangeEnd: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE sourceLineHash = :hash LIMIT 1")
    suspend fun getByLineHash(hash: String): EventEntity?

    @Query("SELECT * FROM events WHERE reminderMinutes >= 0")
    suspend fun getWithReminders(): List<EventEntity>

    @Query(
        """SELECT * FROM events WHERE title LIKE '%' || :q || '%'
           OR tags LIKE '%' || :q || '%'
           OR description LIKE '%' || :q || '%'"""
    )
    fun search(q: String): Flow<List<EventEntity>>

    @Query("DELETE FROM events")
    suspend fun deleteAll()
}
