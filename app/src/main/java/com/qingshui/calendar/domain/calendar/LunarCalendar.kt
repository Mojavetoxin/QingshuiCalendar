package com.qingshui.calendar.domain.calendar

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 公历 -> 农历 / 干支 / 节气（1900-3000）。
 * 算法与数据表移植自 calendar.js（MIT License, https://github.com/jjonline/calendar.js），
 * 移植后经 2024-2027 春节、2025 闰六月、2033 闰冬月、2026 清明/冬至等锚点核验通过。
 */
object LunarCalendar {

    private val BASE: LocalDate = LocalDate.of(1900, 1, 31) // 1900-01-31 = 农历正月初一

    data class LunarDate(val year: Int, val month: Int, val day: Int, val isLeap: Boolean)

    // ---------- 底层位运算（与 calendar.js 完全一致） ----------

    private fun leapMonth(y: Int): Int = LUNAR_INFO[y - 1900] and 0xf

    private fun leapDays(y: Int): Int =
        if (leapMonth(y) != 0) (if ((LUNAR_INFO[y - 1900] and 0x10000) != 0) 30 else 29) else 0

    private fun monthDays(y: Int, m: Int): Int =
        if ((LUNAR_INFO[y - 1900] and (0x10000 shr m)) != 0) 30 else 29

    private fun yearDays(y: Int): Int {
        var sum = 348
        var i = 0x8000
        while (i > 0x8) {
            if ((LUNAR_INFO[y - 1900] and i) != 0) sum++
            i = i shr 1
        }
        return sum + leapDays(y)
    }

    // ---------- 公历转农历 ----------

    fun from(date: LocalDate): LunarDate? {
        val maxYear = 1900 + LUNAR_INFO.size - 1
        if (date.isBefore(BASE) || date.year > maxYear) return null

        var offset = ChronoUnit.DAYS.between(BASE, date)
        var i = 1900
        var temp = 0
        while (offset > 0 && i <= maxYear) {
            temp = yearDays(i)
            offset -= temp
            i++
        }
        if (offset < 0) {
            offset += temp
            i--
        }
        val lunarYear = i
        val leap = leapMonth(lunarYear)
        var isLeap = false
        var m = 1
        while (m < 13 && offset > 0) {
            if (leap > 0 && m == leap + 1 && !isLeap) {
                m--
                isLeap = true
                temp = leapDays(lunarYear)
            } else {
                temp = monthDays(lunarYear, m)
            }
            if (isLeap && m == leap + 1) {
                isLeap = false
            }
            offset -= temp
            m++
        }
        if (offset == 0L && leap > 0 && m == leap + 1) {
            if (isLeap) {
                isLeap = false
            } else {
                isLeap = true
                m--
            }
        }
        if (offset < 0) {
            offset += temp
            m--
        }
        return LunarDate(lunarYear, m, offset.toInt() + 1, isLeap)
    }

    // ---------- 中文显示 ----------

    private val CN_MONTH = listOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")
    private val CN_NUM = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
    private val GAN = listOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
    private val ZHI = listOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
    private val ANIMALS = listOf("鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪")

    fun monthName(l: LunarDate): String =
        (if (l.isLeap) "闰" else "") + CN_MONTH[l.month - 1] + "月"

    fun dayName(day: Int): String = when (day) {
        10 -> "初十"
        20 -> "二十"
        30 -> "三十"
        else -> (if (day < 10) "初" else if (day < 20) "十" else if (day < 30) "廿" else "卅") +
            CN_NUM[day % 10 - 1]
    }

    /** 干支纪年，如 2026 -> 丙午 */
    fun ganZhiYear(y: Int): String = GAN[(y - 4).mod(10)] + ZHI[(y - 4).mod(12)]

    /**
     * 干支纪日，如 2026-10-05 -> 壬子（界面显示时补「日」字）。
     * 基准：2000-01-01 为戊午日（干支序号 54），每过一天序号 +1，六十天一循环。
     */
    fun ganZhiDay(date: LocalDate): String {
        val diff = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(2000, 1, 1), date)
        val idx = ((54 + diff) % 60 + 60) % 60
        return GAN[(idx % 10).toInt()] + ZHI[(idx % 12).toInt()]
    }

    /**
     * 干支纪月（五虎遁）：年干定正月天干（甲己→丙寅、乙庚→戊寅、丙辛→庚寅、丁壬→壬寅、戊癸→甲寅），
     * 月支固定「正月起寅」。如 2026 年（丙午）农历八月 -> 丁酉。
     */
    fun ganZhiMonth(year: Int, lunarMonth: Int): String {
        val m = ((lunarMonth - 1) % 12 + 12) % 12
        val yGan = (year - 4).mod(10)              // 0 = 甲
        val firstGan = (yGan * 2 + 2) % 10         // 正月（寅月）的天干
        return GAN[(firstGan + m) % 10] + ZHI[(m + 2) % 12]
    }

    /** 农历月日连写，如「八月廿五」；越界返回空串 */
    fun lunarMonthDay(date: LocalDate): String {
        val l = from(date) ?: return ""
        return monthName(l) + dayName(l.day)
    }

    /** 只取农历月名，如「八月」；越界返回空串 */
    fun lunarMonthName(date: LocalDate): String {
        val l = from(date) ?: return ""
        return monthName(l)
    }

    fun animalYear(y: Int): String = ANIMALS[(y - 4).mod(12)]

    // ---------- 节气 ----------

    private val termCache = HashMap<Int, IntArray>()

    /** 解码某年 24 个节气「日」；下标 0 = 小寒。月固定 = (n+1)/2 */
    private fun termDaysOfYear(year: Int): IntArray? {
        if (year < 1900 || year > 1900 + SOLAR_TERM_INFO.size - 1) return null
        termCache[year]?.let { return it }
        val table = SOLAR_TERM_INFO[year - 1900]
        val days = IntArray(24)
        var p = 0
        var i = 0
        while (i + 5 <= table.length) {
            val chunk = table.substring(i, i + 5).toLong(16).toString()
            days[p++] = chunk[0] - '0'
            days[p++] = chunk.substring(1, 3).toInt()
            days[p++] = chunk[3] - '0'
            days[p++] = chunk.substring(4, 6).toInt()
            i += 5
        }
        termCache[year] = days
        return days
    }

    /** 该日期若是节气，返回节气名（如 清明），否则 null */
    fun solarTermName(date: LocalDate): String? {
        val days = termDaysOfYear(date.year) ?: return null
        val month = date.monthValue
        // 该月的两个节气：n = 2*month-1 与 2*month
        val n1 = month * 2 - 1
        val n2 = month * 2
        if (days[n1 - 1] == date.dayOfMonth) return SOLAR_TERM_NAMES[n1 - 1]
        if (days[n2 - 1] == date.dayOfMonth) return SOLAR_TERM_NAMES[n2 - 1]
        return null
    }
}
