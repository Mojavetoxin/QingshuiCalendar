package com.qingshui.calendar.domain.model

import java.time.LocalDate
import java.time.LocalTime

/**
 * 一句话解析结果。
 * ok=false 时 reason 给出原因，raw 保留原文供手动编辑。
 */
data class QuickAddResult(
    val ok: Boolean,
    val title: String = "",
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val endTime: LocalTime? = null,
    val repeat: RepeatType = RepeatType.NONE,
    val repeatDays: Set<Int> = emptySet(),  // CUSTOM 时生效，ISO 星期编号 1(一)..7(日)
    val tags: List<String> = emptyList(),
    val reminderMinutes: Int? = null,       // null = 原文未指定，保存时用设置里的默认值
    val reason: String = "",
    val raw: String = ""
)
