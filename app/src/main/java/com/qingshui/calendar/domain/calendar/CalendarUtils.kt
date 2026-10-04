package com.qingshui.calendar.domain.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields

/** 月历网格、星期表头、周数等纯计算工具 */
object CalendarUtils {

    /** 某月 6x7 网格的起始日（含月首前的补位日） */
    fun gridStart(month: YearMonth, weekStartMonday: Boolean): LocalDate {
        val target = if (weekStartMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
        var d = month.atDay(1)
        while (d.dayOfWeek != target) d = d.minusDays(1)
        return d
    }

    /** 某月 6x7=42 个网格日期 */
    fun gridDates(month: YearMonth, weekStartMonday: Boolean): List<LocalDate> {
        val start = gridStart(month, weekStartMonday)
        return (0 until 42).map { start.plusDays(it.toLong()) }
    }

    /** 星期表头（一 二 三 四 五 六 日 / 日 一 二 ... 六） */
    fun weekdayLabels(weekStartMonday: Boolean): List<String> {
        val names = listOf("一", "二", "三", "四", "五", "六", "日") // 对应 MONDAY..SUNDAY
        val order = if (weekStartMonday) listOf(1, 2, 3, 4, 5, 6, 7) else listOf(7, 1, 2, 3, 4, 5, 6)
        return order.map { names[it - 1] }
    }

    /** ISO 周数（按周起始日设置选择规则） */
    fun weekNumber(date: LocalDate, weekStartMonday: Boolean): Int {
        return if (weekStartMonday) {
            date.get(WeekFields.ISO.weekOfWeekBasedYear())
        } else {
            date.get(WeekFields.of(DayOfWeek.SUNDAY, 1).weekOfWeekBasedYear())
        }
    }

    /** 「10月4日 周日」这种分组头 */
    fun dateHeader(date: LocalDate, today: LocalDate): String {
        val week = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[date.dayOfWeek.value - 1]
        val suffix = when (date) {
            today -> " · 今天"
            today.plusDays(1) -> " · 明天"
            today.minusDays(1) -> " · 昨天"
            else -> ""
        }
        return "${date.monthValue}月${date.dayOfMonth}日 $week$suffix"
    }

    fun timeText(t: java.time.LocalTime?): String =
        t?.let { String.format("%02d:%02d", it.hour, it.minute) } ?: "全天"
}
