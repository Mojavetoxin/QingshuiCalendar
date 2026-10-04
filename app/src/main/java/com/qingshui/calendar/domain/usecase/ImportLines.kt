package com.qingshui.calendar.domain.usecase

import com.qingshui.calendar.data.local.entity.EventEntity
import com.qingshui.calendar.data.repository.EventRepository
import com.qingshui.calendar.data.repository.ImportRecordRepository
import com.qingshui.calendar.domain.model.EventDraft
import com.qingshui.calendar.domain.model.EventSource
import com.qingshui.calendar.domain.model.QuickAddResult
import com.qingshui.calendar.domain.parse.NaturalLanguageParser
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 文档逐行导入用例（手动「立即读取」与每日后台任务共用）：
 * 行 → 规范化 hash → 去重 → 解析 → 入库 / 进待处理列表。
 * 去重键 = (sourceUri, sha256("uri|规范化行"))；同一行内容重复导入不会产生重复日程。
 */
class ImportLinesUseCase(
    private val eventRepository: EventRepository,
    private val importRecordRepository: ImportRecordRepository,
    private val defaultReminderProvider: () -> Int = { 15 }
) {

    companion object {
        const val STATUS_OK = "OK"
        const val STATUS_DUPLICATE = "DUPLICATE"
        const val STATUS_PENDING = "PENDING"
    }

    data class LineResult(
        val line: String,
        val status: String,          // STATUS_OK / STATUS_DUPLICATE / STATUS_PENDING
        val eventId: Long? = null,
        val reason: String = ""
    )

    suspend fun run(
        sourceUri: String,
        lines: List<String>,
        today: LocalDate = LocalDate.now()
    ): List<LineResult> {
        val out = mutableListOf<LineResult>()
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val hash = lineHash(sourceUri, line)

            if (importRecordRepository.countByHash(sourceUri, hash) > 0) {
                out.add(LineResult(line, "DUPLICATE"))
                continue
            }

            val r = NaturalLanguageParser.parse(line, today)
            if (!r.ok) {
                importRecordRepository.markPending(sourceUri, hash, line, r.reason)
                out.add(LineResult(line, "PENDING", reason = r.reason))
                continue
            }

            val entity = draftToEntity(quickAddToDraft(r, defaultReminderProvider()), EventSource.DOCUMENT, lineHash = hash)
            val id = eventRepository.upsert(entity)
            importRecordRepository.markSuccess(sourceUri, hash, line, id)
            out.add(LineResult(line, "OK", eventId = id))
        }
        return out
    }
}

/** sha256("uri|规范化后的行内容") */
fun lineHash(sourceUri: String, normalizedLine: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest("$sourceUri|$normalizedLine".toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

/** 一句话解析结果 → 编辑器草稿（reminderMinutes 为 null 时用默认提醒） */
fun quickAddToDraft(r: QuickAddResult, defaultReminderMinutes: Int): EventDraft {
    val date = r.date ?: LocalDate.now()
    return EventDraft(
        title = r.title,
        description = "",
        date = date,
        allDay = r.time == null,
        startTime = r.time ?: LocalTime.of(0, 0),
        endTime = r.endTime ?: (r.time?.plusHours(1) ?: LocalTime.of(1, 0)),
        location = "",
        reminderMinutes = r.reminderMinutes ?: defaultReminderMinutes,
        repeat = r.repeat,
        repeatDays = r.repeatDays,
        tags = r.tags
    )
}

/** 草稿 → 数据库实体（全天 = 当天 00:00 ~ 次日 00:00；跨天时段自动 +1 天） */
fun draftToEntity(
    d: EventDraft,
    source: EventSource,
    existingId: Long? = null,
    lineHash: String? = null
): EventEntity {
    val zone = ZoneId.systemDefault()
    var start: java.time.LocalDateTime
    var end: java.time.LocalDateTime
    if (d.allDay) {
        start = d.date.atStartOfDay()
        end = d.date.plusDays(1).atStartOfDay()
    } else {
        start = d.date.atTime(d.startTime)
        end = d.date.atTime(d.endTime)
        if (!end.isAfter(start)) end = end.plusDays(1)
    }
    return EventEntity(
        id = existingId ?: 0L,
        title = d.title.trim().ifEmpty { "未命名日程" },
        description = d.description,
        startTime = start.atZone(zone).toInstant().toEpochMilli(),
        endTime = end.atZone(zone).toInstant().toEpochMilli(),
        allDay = d.allDay,
        location = d.location,
        reminderMinutes = d.reminderMinutes,
        repeatType = d.repeat.name,
        repeatDaysOfWeek = d.repeatDays.sorted().joinToString(","),
        tags = d.tags.joinToString(","),
        color = d.color,
        status = d.status,
        source = source.name,
        sourceLineHash = lineHash
    )
}
