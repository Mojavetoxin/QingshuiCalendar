package com.qingshui.calendar.domain.repeat

import com.qingshui.calendar.data.local.entity.EventEntity
import com.qingshui.calendar.domain.model.EventOccurrence
import com.qingshui.calendar.domain.model.RepeatType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 重复日程展开器。
 * 数据库只存规则，查询区间时在内存展开成具体的日期。
 * 展开数量有上限保护，避免永久重复 + 大区间导致卡顿。
 */
object RepeatExpander {

    const val MAX_OCCURRENCES = 500

    /** 把一批日程在 [rangeStart, rangeEnd] 内展开为出现列表 */
    fun expand(
        events: List<EventEntity>,
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
        zone: ZoneId
    ): List<EventOccurrence> {
        val out = ArrayList<EventOccurrence>()
        for (e in events) {
            val startDate = fromMillis(e.startTime, zone).toLocalDate()
            val spanDays = coveredEndDate(e, zone).toEpochDay() - startDate.toEpochDay()
            for (d in occurrenceDates(e, rangeStart, rangeEnd, zone)) {
                val occEnd = d.plusDays(spanDays)
                out.add(
                    EventOccurrence(
                        event = e,
                        date = d,
                        isStartDay = d == startDate || e.repeatType != "NONE",
                        isEndDay = !occEnd.isAfter(d) || occEnd == d
                    )
                )
            }
        }
        return out
    }

    /** 日程的覆盖结束日（含）。endTime 若恰为 00:00 视为排他（即前一天结束） */
    fun coveredEndDate(e: EventEntity, zone: ZoneId): LocalDate {
        val endLdt = fromMillis(e.endTime, zone)
        val d = endLdt.toLocalDate()
        return if (endLdt.toLocalTime() == LocalTime.MIDNIGHT) d.minusDays(1) else d
    }

    /**
     * 事件在 [rangeStart, rangeEnd] 内的出现日期（按开始日期算）。
     */
    fun occurrenceDates(
        e: EventEntity,
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
        zone: ZoneId
    ): List<LocalDate> {
        val start = fromMillis(e.startTime, zone).toLocalDate()
        val repeatEndDate = e.repeatEndTime?.let { fromMillis(it, zone).toLocalDate() }
        val effectiveEnd = if (repeatEndDate != null && repeatEndDate.isBefore(rangeEnd)) repeatEndDate else rangeEnd
        if (start.isAfter(effectiveEnd)) return emptyList()

        val type = runCatching { RepeatType.valueOf(e.repeatType) }.getOrDefault(RepeatType.NONE)
        val out = ArrayList<LocalDate>()

        when (type) {
            RepeatType.NONE -> {
                if (!start.isBefore(rangeStart) && !start.isAfter(effectiveEnd)) out.add(start)
            }

            RepeatType.DAILY -> {
                val interval = e.repeatInterval.coerceAtLeast(1).toLong()
                if (interval == 1L) {
                    var d = maxOf(start, rangeStart)
                    while (!d.isAfter(effectiveEnd) && out.size < MAX_OCCURRENCES) {
                        out.add(d); d = d.plusDays(1)
                    }
                } else {
                    // 保持相位对齐：从起点按间隔跳到区间内第一个
                    val gap = ChronoUnit.DAYS.between(start, rangeStart)
                    var d = if (gap <= 0) start
                    else start.plusDays(((gap + interval - 1) / interval) * interval)
                    while (!d.isAfter(effectiveEnd) && out.size < MAX_OCCURRENCES) {
                        out.add(d); d = d.plusDays(interval)
                    }
                }
            }

            RepeatType.WEEKLY -> {
                val step = 7L * e.repeatInterval.coerceAtLeast(1)
                val gap = ChronoUnit.DAYS.between(start, rangeStart)
                var d = if (gap <= 0) start
                else start.plusDays(((gap + step - 1) / step) * step)
                while (!d.isAfter(effectiveEnd) && out.size < MAX_OCCURRENCES) {
                    out.add(d); d = d.plusDays(step)
                }
            }

            RepeatType.WEEKDAY -> {
                var d = maxOf(start, rangeStart)
                while (!d.isAfter(effectiveEnd) && out.size < MAX_OCCURRENCES) {
                    if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out.add(d)
                    d = d.plusDays(1)
                }
            }

            RepeatType.MONTHLY -> {
                val anchorDay = start.dayOfMonth
                var ym = YearMonth.from(start)
                val endYm = YearMonth.from(effectiveEnd)
                var guard = 0
                while (!ym.isAfter(endYm) && out.size < MAX_OCCURRENCES && guard < 20000) {
                    val date = ym.atDay(minOf(anchorDay, ym.lengthOfMonth()))
                    if (!date.isBefore(rangeStart) && !date.isAfter(effectiveEnd)) out.add(date)
                    ym = ym.plusMonths(e.repeatInterval.coerceAtLeast(1).toLong())
                    guard++
                }
            }

            RepeatType.YEARLY -> {
                var y = start.year
                var guard = 0
                while (y <= effectiveEnd.year && out.size < MAX_OCCURRENCES && guard < 5000) {
                    val day = if (start.month == java.time.Month.FEBRUARY && start.dayOfMonth == 29 && !Year.isLeap(y.toLong())) 28
                    else start.dayOfMonth
                    val date = LocalDate.of(y, start.month, day)
                    if (!date.isBefore(rangeStart) && !date.isAfter(effectiveEnd)) out.add(date)
                    y += e.repeatInterval.coerceAtLeast(1)
                    guard++
                }
            }

            RepeatType.CUSTOM -> {
                val days = parseDays(e.repeatDaysOfWeek)
                var d = maxOf(start, rangeStart)
                while (!d.isAfter(effectiveEnd) && out.size < MAX_OCCURRENCES) {
                    if (d.dayOfWeek.value in days) out.add(d)
                    d = d.plusDays(1)
                }
            }
        }
        return out
    }

    /**
     * 下一次出现的开始时刻（用于提醒调度）。
     * 从 [after] 起向后找最多 400 天；找不到返回 null。
     */
    fun nextOccurrenceStart(e: EventEntity, after: LocalDateTime, zone: ZoneId): LocalDateTime? {
        val begin = after.toLocalDate()
        val dates = occurrenceDates(e, begin, begin.plusDays(400), zone)
        for (d in dates) {
            val timeOfDay = fromMillis(e.startTime, zone).toLocalTime()
            val startLdt = d.atTime(timeOfDay)
            if (startLdt.isAfter(after)) return startLdt
        }
        return null
    }

    fun parseDays(csv: String): Set<Int> =
        csv.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()

    private fun fromMillis(ms: Long, zone: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
}
