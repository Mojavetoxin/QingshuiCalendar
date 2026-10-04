package com.qingshui.calendar.domain.model

import java.time.LocalDate

/**
 * 某一天上「日程的一次出现」。
 * 重复日程按日期展开成多次出现；跨天日程在每个覆盖日各出现一次。
 */
data class EventOccurrence(
    val event: com.qingshui.calendar.data.local.entity.EventEntity,
    val date: LocalDate,        // 出现日期
    val isStartDay: Boolean,    // 该日是否为日程开始日
    val isEndDay: Boolean       // 该日是否为日程结束日
)
