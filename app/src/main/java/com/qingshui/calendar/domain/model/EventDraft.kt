package com.qingshui.calendar.domain.model

import java.time.LocalDate
import java.time.LocalTime

/**
 * 编辑器草稿。一句话导入的预览、待处理行手动编辑都会先转成草稿，
 * 再交给日程编辑页确认保存。
 */
data class EventDraft(
    val id: Long? = null,
    val title: String = "",
    val description: String = "",
    val date: LocalDate = LocalDate.now(),
    val allDay: Boolean = true,
    val startTime: LocalTime = LocalTime.of(9, 0),
    val endTime: LocalTime = LocalTime.of(10, 0),
    val location: String = "",
    val reminderMinutes: Int = 15,
    val repeat: RepeatType = RepeatType.NONE,
    val repeatDays: Set<Int> = emptySet(),
    val tags: List<String> = emptyList(),
    val color: Int = EventColors.default(),
    val status: Int = 0
)
