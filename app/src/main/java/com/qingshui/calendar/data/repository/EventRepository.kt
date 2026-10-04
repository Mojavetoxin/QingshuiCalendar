package com.qingshui.calendar.data.repository

import com.qingshui.calendar.data.local.EventDao
import com.qingshui.calendar.data.local.entity.EventEntity
import com.qingshui.calendar.domain.model.EventOccurrence
import com.qingshui.calendar.domain.repeat.RepeatExpander
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 日程仓库：DAO 之上的薄封装，统一处理时区与重复展开 */
class EventRepository(
    private val dao: EventDao,
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    /**
     * 观察一段日期内的日程（重复规则在内存展开）。
     * @param startInclusive 起始日（含）
     * @param endInclusive   结束日（含）
     */
    fun observeBetween(startInclusive: LocalDate, endInclusive: LocalDate): Flow<List<EventOccurrence>> {
        val from = startInclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return dao.observeInRange(from, to)
            .map { RepeatExpander.expand(it, startInclusive, endInclusive, zone) }
    }

    fun observeAll(): Flow<List<EventEntity>> = dao.observeAll()

    fun search(q: String): Flow<List<EventEntity>> = dao.search(q)

    suspend fun get(id: Long): EventEntity? = dao.getById(id)

    suspend fun getAllOnce(): List<EventEntity> = dao.getAll()

    /** 新增或更新（id==0 视为新增）；返回最终 id */
    suspend fun upsert(e: EventEntity): Long =
        if (e.id == 0L) dao.insert(e) else {
            dao.update(e.copy(updatedAt = System.currentTimeMillis()))
            e.id
        }

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()

    // ---- 时间换算工具（全 App 统一时区口径） ----

    fun millisOf(date: LocalDate, time: LocalTime): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    fun dateOf(ms: Long): LocalDate =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun timeOf(ms: Long): LocalTime =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
}
