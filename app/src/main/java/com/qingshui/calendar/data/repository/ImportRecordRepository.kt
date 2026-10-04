package com.qingshui.calendar.data.repository

import com.qingshui.calendar.data.local.ImportRecordDao
import com.qingshui.calendar.data.local.entity.ImportRecordEntity
import kotlinx.coroutines.flow.Flow

/** 导入记录仓库：负责 (sourceUri, lineHash) 去重记录的状态流转 */
class ImportRecordRepository(private val dao: ImportRecordDao) {

    fun observePending(): Flow<List<ImportRecordEntity>> = dao.observePending()

    suspend fun findByHash(uri: String, hash: String): ImportRecordEntity? =
        dao.findByHash(uri, hash)

    suspend fun countByHash(uri: String, hash: String): Int = dao.countByHash(uri, hash)

    /** 标记为导入成功（status=0），关联生成的日程 id */
    suspend fun markSuccess(uri: String, hash: String, raw: String, eventId: Long) {
        val existing = dao.findByHash(uri, hash)
        if (existing == null) {
            dao.insert(
                ImportRecordEntity(
                    sourceUri = uri, lineHash = hash, rawText = raw,
                    eventId = eventId, status = 0
                )
            )
        } else {
            dao.update(
                existing.copy(
                    eventId = eventId, status = 0, reason = "",
                    processedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /** 标记为待处理（status=1，解析失败） */
    suspend fun markPending(uri: String, hash: String, raw: String, reason: String) {
        val existing = dao.findByHash(uri, hash)
        if (existing == null) {
            dao.insert(
                ImportRecordEntity(
                    sourceUri = uri, lineHash = hash, rawText = raw,
                    eventId = null, status = 1, reason = reason
                )
            )
        } else {
            dao.update(
                existing.copy(
                    status = 1, reason = reason,
                    processedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /** 标记为已忽略（status=2） */
    suspend fun markIgnored(record: ImportRecordEntity) {
        dao.update(record.copy(status = 2, processedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()
}
