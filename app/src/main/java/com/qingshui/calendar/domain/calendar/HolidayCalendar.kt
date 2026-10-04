package com.qingshui.calendar.domain.calendar

import java.time.LocalDate

/**
 * 中国节假日 / 调休。
 *
 * - 2026 年：国务院办公厅 2025-11-04《关于2026年部分节假日安排的通知》官方数据，
 *   经人民网、央视网、光明网多个来源交叉核对（含全部调休上班日）。
 * - 2027 年及以后：官方调休安排尚未公布，按法定节日当天自动推算
 *   （元旦 / 春节除夕~初三 / 清明 / 劳动节 / 端午 / 中秋 / 国庆）。
 *   每年新安排公布后，可仿照 y2026 的写法在 overrides 中补充精确调休。
 */
object HolidayCalendar {

    data class DayInfo(val name: String, val isOffDay: Boolean)

    // 2026 年官方安排
    private val y2026: Map<LocalDate, DayInfo> = buildMap {
        // 元旦：1/1（周四）-1/3 放假；1/4（周日）上班
        (1..3).forEach { put(LocalDate.of(2026, 1, it), DayInfo("元旦", true)) }
        put(LocalDate.of(2026, 1, 4), DayInfo("元旦调休", false))
        // 春节：2/15（腊月廿八，周日）-2/23（正月初七）放假 9 天；2/14、2/28（周六）上班
        (15..23).forEach { put(LocalDate.of(2026, 2, it), DayInfo("春节", true)) }
        put(LocalDate.of(2026, 2, 14), DayInfo("春节调休", false))
        put(LocalDate.of(2026, 2, 28), DayInfo("春节调休", false))
        // 清明：4/4（周六）-4/6 放假
        (4..6).forEach { put(LocalDate.of(2026, 4, it), DayInfo("清明节", true)) }
        // 劳动节：5/1（周五）-5/5 放假；5/9（周六）上班
        (1..5).forEach { put(LocalDate.of(2026, 5, it), DayInfo("劳动节", true)) }
        put(LocalDate.of(2026, 5, 9), DayInfo("劳动节调休", false))
        // 端午：6/19（周五）-6/21 放假
        (19..21).forEach { put(LocalDate.of(2026, 6, it), DayInfo("端午节", true)) }
        // 中秋：9/25（周五）-9/27 放假
        (25..27).forEach { put(LocalDate.of(2026, 9, it), DayInfo("中秋节", true)) }
        // 国庆：10/1（周四）-10/7 放假；9/20（周日）、10/10（周六）上班
        (1..7).forEach { put(LocalDate.of(2026, 10, it), DayInfo("国庆节", true)) }
        put(LocalDate.of(2026, 9, 20), DayInfo("国庆调休", false))
        put(LocalDate.of(2026, 10, 10), DayInfo("国庆调休", false))
    }

    private val cnyCache = HashMap<Int, LocalDate>()

    /** 查询某天的节假日信息；普通日子返回 null */
    fun info(date: LocalDate): DayInfo? {
        if (date.year == 2026) return y2026[date]
        return statutory(date)
    }

    /** 是否调休上班日（显示「班」） */
    fun isMakeupWorkday(date: LocalDate): Boolean = info(date)?.isOffDay == false

    /** 2027+ 法定节日推算（不含调休连休，因为安排未公布） */
    private fun statutory(date: LocalDate): DayInfo? {
        val y = date.year
        if (y < 2027 || y > 2100) return null
        val cny = chineseNewYear(y)
        return when {
            date.monthValue == 1 && date.dayOfMonth == 1 -> DayInfo("元旦", true)
            date == cny.minusDays(1) -> DayInfo("除夕", true)
            date == cny || date == cny.plusDays(1) || date == cny.plusDays(2) -> DayInfo("春节", true)
            date.monthValue == 5 && date.dayOfMonth <= 2 -> DayInfo("劳动节", true)
            date.monthValue == 10 && date.dayOfMonth <= 3 -> DayInfo("国庆节", true)
            else -> lunarFestival(date)
        }
    }

    private fun chineseNewYear(y: Int): LocalDate {
        cnyCache[y]?.let { return it }
        var d = LocalDate.of(y, 1, 1)
        var found: LocalDate? = null
        for (i in 0 until 80) {
            val l = LunarCalendar.from(d)
            if (l != null && l.month == 1 && l.day == 1 && !l.isLeap) {
                found = d
                break
            }
            d = d.plusDays(1)
        }
        val result = found ?: d
        cnyCache[y] = result
        return result
    }

    private fun lunarFestival(date: LocalDate): DayInfo? {
        val l = LunarCalendar.from(date) ?: return null
        if (!l.isLeap) {
            if (l.month == 5 && l.day == 5) return DayInfo("端午节", true)
            if (l.month == 8 && l.day == 15) return DayInfo("中秋节", true)
        }
        if (LunarCalendar.solarTermName(date) == "清明") return DayInfo("清明节", true)
        return null
    }
}
