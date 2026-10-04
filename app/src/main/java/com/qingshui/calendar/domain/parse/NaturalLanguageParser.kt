package com.qingshui.calendar.domain.parse

import com.qingshui.calendar.domain.model.QuickAddResult
import com.qingshui.calendar.domain.model.RepeatType
import java.time.LocalDate
import java.time.LocalTime

/**
 * 一句话日程解析器（纯本地规则，离线可用，绝不抛异常）。
 *
 * 流程：全角归一化 → #标签 → 提醒 → 重复 → 日期 → 时间 → 清理标题。
 * 识别不了的字段留空并在 reason 里说明；标题或日期缺失则 ok=false。
 *
 * 支持的说法举例：
 *  日期：明天/后天/大后天/昨天/前天/今天、5月20日、2026-05-01、下周五、周三、
 *       下个月15号、3天后、今晚/明晚
 *  时间：下午3点、10点半、9:30、晚上8点、下午2点到4点、中午12点
 *  重复：每天、每周三、每个工作日、每周末、每月15号、每年6月1日
 *  提醒：提前30分钟提醒、提前1小时提醒、提前1天提醒、半小时前提醒、准点提醒、不提醒
 *  标签：#工作 #健身
 */
object NaturalLanguageParser {

    private const val NUM = "[0-9零一二两三四五六七八九十]{1,3}"
    private const val PERIOD = "(?:凌晨|清晨|早上|上午|中午|下午|傍晚|晚上|夜里)"
    private const val NUM_PART = "(?:\\d{1,2}|[零一二两三四五六七八九十]{1,3})"

    // ---------------------------------------------------------------- 入口

    fun parse(raw: String, today: LocalDate = LocalDate.now()): QuickAddResult {
        val original = raw
        var work = normalize(raw)
        if (work.isEmpty()) {
            return QuickAddResult(false, reason = "没有输入内容", raw = original)
        }

        // 1) 标签：#xxx
        val tagRe = Regex("#([^\\s#]+)")
        val tags = tagRe.findAll(work)
            .map { it.groupValues[1].trim('，', ',', '。', '.', '、') }
            .filter { it.isNotEmpty() }
            .toList()
        if (tags.isNotEmpty()) work = tagRe.replace(work, " ")

        // 2) 提醒
        var reminder: Int? = null
        for ((re, calc) in REMINDER_PATTERNS) {
            val m = re.find(work) ?: continue
            val v = calc(m)
            work = work.replaceRange(m.range, " ")
            if (v < -1 || v > 60 * 24 * 30) {
                return QuickAddResult(false, tags = tags, reason = "提醒时间无法识别（最多支持提前 30 天）", raw = original)
            }
            reminder = v
            break
        }

        // 3) 重复
        var repeat = RepeatType.NONE
        var repeatDays = emptySet<Int>()
        var monthlyDay: Int? = null
        for (rp in REPEAT_PATTERNS) {
            val m = rp.re.find(work) ?: continue
            when (rp.kind) {
                "Y" -> repeat = RepeatType.YEARLY
                "M" -> {
                    repeat = RepeatType.MONTHLY
                    monthlyDay = toNum(m.groupValues[1])
                }
                "Mb" -> repeat = RepeatType.MONTHLY
                "WE" -> {
                    repeat = RepeatType.CUSTOM
                    repeatDays = setOf(6, 7)
                }
                "WD" -> repeat = RepeatType.WEEKDAY
                "W" -> {
                    repeat = RepeatType.WEEKLY
                    dowOf(m.groupValues[1])?.let { repeatDays = setOf(it) }
                }
                "Wb" -> repeat = RepeatType.WEEKLY
                "D" -> repeat = RepeatType.DAILY
            }
            work = work.replaceRange(m.range, " ")
            break
        }
        if (repeat == RepeatType.MONTHLY && monthlyDay != null && monthlyDay !in 1..31) {
            return QuickAddResult(false, tags = tags, reason = "每月重复的日期「$monthlyDay 号」无法识别", raw = original)
        }

        // 4) 日期（按优先级逐个尝试，命中即消费）
        var date: LocalDate? = null
        var eveningHint = false

        if (date == null) {
            val m = Regex("(?<!\\d)(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?!\\d)").find(work)
            if (m != null) {
                val ld = safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                    ?: return QuickAddResult(false, tags = tags, reason = "日期「${m.value}」无法识别", raw = original)
                date = ld
                work = work.replaceRange(m.range, " ")
            }
        }

        if (date == null) {
            val m = Regex("(\\d{4})\\s*年\\s*($NUM)\\s*月\\s*($NUM)\\s*[日号]?").find(work)
            if (m != null) {
                val y = m.groupValues[1].toInt()
                val mo = toNum(m.groupValues[2])
                val d = toNum(m.groupValues[3])
                if (mo == null || mo !in 1..12 || d == null)
                    return QuickAddResult(false, tags = tags, reason = "日期「${m.value.trim()}」无法识别", raw = original)
                val ld = safeDate(y, mo, d)
                    ?: return QuickAddResult(false, tags = tags, reason = "「${y}年${mo}月${d}日」不存在", raw = original)
                date = ld
                work = work.replaceRange(m.range, " ")
            }
        }

        if (date == null) {
            val m = Regex("下个?月\\s*($NUM)?\\s*[日号]?").find(work)
            if (m != null) {
                val nm = today.plusMonths(1)
                val day = m.groupValues[1]?.let { toNum(it) }
                date = if (day != null) {
                    if (day !in 1..31) {
                        return QuickAddResult(false, tags = tags, reason = "「下个月$day 号」无法识别", raw = original)
                    }
                    nm.withDayOfMonth(minOf(day, nm.lengthOfMonth()))
                } else {
                    nm.withDayOfMonth(minOf(today.dayOfMonth, nm.lengthOfMonth()))
                }
                work = work.replaceRange(m.range, " ")
            }
        }

        if (date == null) {
            val m = Regex("(?<![0-9年])($NUM)\\s*月\\s*($NUM)\\s*[日号]?").find(work)
            if (m != null) {
                val mo = toNum(m.groupValues[1])
                val d = toNum(m.groupValues[2])
                if (mo == null || mo !in 1..12)
                    return QuickAddResult(false, tags = tags, reason = "月份「${m.groupValues[1]}」无法识别", raw = original)
                if (d == null || d !in 1..31)
                    return QuickAddResult(false, tags = tags, reason = "日期「${m.groupValues[1]}月${m.groupValues[2]}」无法识别", raw = original)
                var ld = safeDate(today.year, mo, d)
                    ?: return QuickAddResult(false, tags = tags, reason = "「${mo}月${d}日」不存在（这个月没有这一天）", raw = original)
                if (ld.isBefore(today)) ld = safeDate(today.year + 1, mo, d) ?: ld
                date = ld
                work = work.replaceRange(m.range, " ")
            }
        }

        if (date == null) {
            for ((re, offset, isEvening) in RELATIVE_PATTERNS) {
                val m = re.find(work) ?: continue
                date = today.plusDays(offset)
                eveningHint = isEvening
                work = work.replaceRange(m.range, " ")
                break
            }
        }

        if (date == null) {
            val m = Regex("(\\d{1,3})\\s*天[之以]?后").find(work)
            if (m != null) {
                val n = m.groupValues[1].toIntOrNull()
                if (n != null && n in 0..365) {
                    date = today.plusDays(n.toLong())
                    work = work.replaceRange(m.range, " ")
                }
            }
        }

        if (date == null) {
            val m = Regex("下个?(?:周|星期|礼拜)([一二三四五六日天])").find(work)
            if (m != null) {
                date = nextWeekDow(today, dowOf(m.groupValues[1])!!)
                work = work.replaceRange(m.range, " ")
            }
        }

        if (date == null) {
            val m = Regex("(?:周|星期|礼拜)([一二三四五六日天])").find(work)
            if (m != null) {
                date = nextDow(today, dowOf(m.groupValues[1])!!)
                work = work.replaceRange(m.range, " ")
            }
        }

        // 重复规则自带默认日期
        if (date == null) {
            date = when {
                repeat == RepeatType.MONTHLY && monthlyDay != null -> {
                    var base = today.withDayOfMonth(minOf(monthlyDay, today.lengthOfMonth()))
                    if (base.isBefore(today)) {
                        val nm = base.plusMonths(1)
                        base = nm.withDayOfMonth(minOf(monthlyDay, nm.lengthOfMonth()))
                    }
                    base
                }
                repeat == RepeatType.WEEKLY && repeatDays.isNotEmpty() -> nextDow(today, repeatDays.first())
                repeat != RepeatType.NONE -> today
                else -> null
            }
        }
        if (date == null) {
            return QuickAddResult(
                false, tags = tags,
                reason = "没有识别到日期（试试：明天 / 下周三 / 5月20日 / 每月15号 / 每年6月1日）",
                raw = original
            )
        }

        // 5) 时间（先试范围，再试单个，最后纯时段词默认值）
        var time: LocalTime? = null
        var endTime: LocalTime? = null

        val token = "(?:$PERIOD)?\\s*(?:$NUM_PART)(?:\\s*[:：]\\s*(?:\\d{1,2})|\\s*点\\s*(?:半|(?:$NUM_PART)\\s*分?)?)?"
        val rangeRe = Regex("($token)\\s*(?:到|至|~|～|-|—|–)\\s*($token)")
        val rm = rangeRe.find(work)
        if (rm != null && rm.value.any { it == '点' || it == ':' || it == '：' || it == '半' }) {
            val left = parseToken(rm.groupValues[1])
            val right = parseToken(rm.groupValues[2])
            val start = left?.let { applyPeriod(it.first, it.second, it.third, eveningHint) }
            var end = right?.let { applyPeriod(it.first, it.second, it.third ?: left!!.third, eveningHint) }
            if (start != null && end != null && !end.isAfter(start) && right!!.third == null) {
                end = end.plusHours(12).takeIf { it.isAfter(start) } ?: end
            }
            if (start != null && end != null && end.isAfter(start)) {
                time = start
                endTime = end
            } else {
                time = start
            }
            work = work.replaceRange(rm.range, " ")
        }

        if (time == null) {
            val dian = DIAN_RE.find(work)
            if (dian != null) {
                val h = toNum(dian.groupValues[2])
                if (h != null) {
                    val min = when {
                        dian.groupValues[3] == "半" -> 30
                        dian.groupValues[4].isNotEmpty() -> toNum(dian.groupValues[4]) ?: 0
                        else -> 0
                    }
                    val t = applyPeriod(h, min, dian.groupValues[1].ifEmpty { null }, eveningHint)
                    if (t != null) {
                        time = t
                        work = work.replaceRange(dian.range, " ")
                    } else {
                        return QuickAddResult(false, tags = tags, reason = "时间「${dian.value.trim()}」无法识别", raw = original)
                    }
                }
            }
        }

        if (time == null) {
            val colon = COLON_RE.find(work)
            if (colon != null) {
                val h = toNum(colon.groupValues[2])
                val min = colon.groupValues[3].toIntOrNull()
                if (h != null && min != null) {
                    val t = applyPeriod(h, min, colon.groupValues[1].ifEmpty { null }, eveningHint)
                    if (t != null) {
                        time = t
                        work = work.replaceRange(colon.range, " ")
                    } else {
                        return QuickAddResult(false, tags = tags, reason = "时间「${colon.value.trim()}」无法识别", raw = original)
                    }
                }
            }
        }

        if (time == null && eveningHint) {
            time = LocalTime.of(20, 0)
        }

        if (time == null) {
            val m = Regex("($PERIOD)").find(work)
            if (m != null) {
                val def = when (m.groupValues[1]) {
                    "中午" -> 12
                    "早上", "上午" -> 9
                    "下午" -> 14
                    "傍晚" -> 18
                    "晚上", "夜里" -> 20
                    else -> null
                }
                if (def != null) {
                    time = LocalTime.of(def, 0)
                    work = work.replaceRange(m.range, " ")
                }
            }
        }

        // 6) 标题
        var title = work.replace(Regex("\\s+"), " ").trim()
        title = title.replace(
            Regex("^(?:请?提醒我|记得?提醒?我?|帮我?记(?:下|个|一下)?|记一下|记个|安排一下?|添加?一下?|新建?一下?日程|创建?日程|新建日程|添加日程|我要|日程)[:：，,\\s]*"),
            ""
        )
        title = title.replace(Regex("^[的，,。.、\\s]+"), "")
        title = title.replace(Regex("[，,。.\\s]+$"), "").trim()

        if (title.isEmpty()) {
            return QuickAddResult(false, tags = tags, reason = "没有识别到日程标题", raw = original)
        }

        return QuickAddResult(
            ok = true,
            title = title,
            date = date,
            time = time,
            endTime = endTime,
            repeat = repeat,
            repeatDays = repeatDays,
            tags = tags,
            reminderMinutes = reminder,
            raw = original
        )
    }

    // ---------------------------------------------------------------- 提醒

    private class Rp(val re: Regex, val calc: (MatchResult) -> Int)

    private val REMINDER_PATTERNS = listOf(
        Rp(Regex("不提醒")) { -1 },
        Rp(Regex("准点提醒|到点提醒|准时提醒")) { 0 },
        Rp(Regex("提前半小时提醒|半小时前提醒")) { 30 },
        Rp(Regex("提前一小时提醒|一小时前提醒")) { 60 },
        Rp(Regex("提前一天提醒|一天前提醒")) { 1440 },
        Rp(Regex("提前(\\d{1,4})个?分钟?提醒")) { it.groupValues[1].toInt() },
        Rp(Regex("提前(\\d{1,3})个?小时提醒")) { it.groupValues[1].toInt() * 60 },
        Rp(Regex("提前(\\d{1,3})天提醒")) { it.groupValues[1].toInt() * 1440 },
        Rp(Regex("(\\d{1,4})个?分钟?前提醒")) { it.groupValues[1].toInt() },
        Rp(Regex("(\\d{1,3})个?小时前提醒")) { it.groupValues[1].toInt() * 60 },
        Rp(Regex("(\\d{1,3})天前提醒")) { it.groupValues[1].toInt() * 1440 }
    )

    // ---------------------------------------------------------------- 重复

    private class RepeatPattern(val re: Regex, val kind: String)

    private val REPEAT_PATTERNS = listOf(
        RepeatPattern(Regex("每一?年"), "Y"),
        RepeatPattern(Regex("每个?月\\s*($NUM)\\s*[日号]"), "M"),
        RepeatPattern(Regex("每个?月(?!\\s*$NUM)"), "Mb"),
        RepeatPattern(Regex("每个?周末"), "WE"),
        RepeatPattern(Regex("每个?工作日"), "WD"),
        RepeatPattern(Regex("每个?(?:周|星期|礼拜)([一二三四五六日天])"), "W"),
        RepeatPattern(Regex("每个?(?:周|星期|礼拜)"), "Wb"),
        RepeatPattern(Regex("每一天|每天|天天"), "D")
    )

    // ---------------------------------------------------------------- 相对日期

    private class Rel(val re: Regex, val offset: Long, val evening: Boolean)

    private val RELATIVE_PATTERNS = listOf(
        Rel(Regex("今晚(?:上)?|今天晚上|今日晚上"), 0L, true),
        Rel(Regex("明晚(?:上)?|明天晚上|明日晚上"), 1L, true),
        Rel(Regex("大前天"), -3L, false),
        Rel(Regex("大后天"), 3L, false),
        Rel(Regex("前天"), -2L, false),
        Rel(Regex("昨天"), -1L, false),
        Rel(Regex("今天|今日"), 0L, false),
        Rel(Regex("明天|明日"), 1L, false),
        Rel(Regex("后天"), 2L, false)
    )

    // ---------------------------------------------------------------- 时间正则

    private val COLON_RE = Regex(
        "($PERIOD)?\\s*(?<!\\d)($NUM_PART)\\s*[:：]\\s*(\\d{1,2})(?!\\d)"
    )

    private val DIAN_RE = Regex(
        "($PERIOD)?\\s*(?<!\\d)($NUM_PART)\\s*点(?:\\s*(半)|(?:\\s*($NUM_PART)\\s*分?))?(?:整)?"
    )

    // ---------------------------------------------------------------- 工具

    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '０' -> sb.append('0'); '１' -> sb.append('1'); '２' -> sb.append('2')
                '３' -> sb.append('3'); '４' -> sb.append('4'); '５' -> sb.append('5')
                '６' -> sb.append('6'); '７' -> sb.append('7'); '８' -> sb.append('8')
                '９' -> sb.append('9')
                '：' -> sb.append(':'); '～' -> sb.append('~'); '－' -> sb.append('-')
                '／' -> sb.append('/')
                '，', '。', '、', '；', '（', '）', '【', '】', '《', '》' -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private val CN_DIGIT = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )

    /** "15" / "十五" / "二十" / "三十五" → 数字；失败返回 null */
    private fun toNum(s: String): Int? {
        val t = s.trim().replace("两", "二")
        if (t.isEmpty()) return null
        if (t.all { it.isDigit() }) return t.toIntOrNull()
        if (t == "十") return 10
        val tenIdx = t.indexOf('十')
        fun seq(str: String): Int? {
            if (str.isEmpty()) return null
            var acc = 0
            for (c in str) acc = acc * 10 + (CN_DIGIT[c] ?: return null)
            return acc
        }
        return when {
            tenIdx < 0 -> seq(t)
            tenIdx == 0 -> 10 + (seq(t.substring(1)) ?: 0)
            tenIdx == t.length - 1 -> (seq(t.substring(0, tenIdx)) ?: return null) * 10
            else -> {
                val tens = seq(t.substring(0, tenIdx)) ?: return null
                val ones = seq(t.substring(tenIdx + 1)) ?: return null
                tens * 10 + ones
            }
        }
    }

    private fun dowOf(c: String): Int? = when (c) {
        "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4
        "五" -> 5; "六" -> 6; "日", "天" -> 7
        else -> null
    }

    /** 下一个（含今天）周 dow */
    private fun nextDow(today: LocalDate, dow: Int): LocalDate {
        val delta = ((dow - today.dayOfWeek.value) + 7) % 7
        return today.plusDays(delta.toLong())
    }

    /** 下周的周 dow（今天周日时，下周一是明天） */
    private fun nextWeekDow(today: LocalDate, dow: Int): LocalDate {
        val d = (8 - today.dayOfWeek.value) % 7
        val nextMonday = today.plusDays((if (d == 0) 7 else d).toLong())
        return nextMonday.plusDays((dow - 1).toLong())
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? =
        try { LocalDate.of(y, m, d) } catch (e: Exception) { null }

    /** 把「原始小时 + 时段词」折算成 0-23 点 */
    private fun applyPeriod(hour: Int, minute: Int, period: String?, eveningHint: Boolean): LocalTime? {
        if (hour > 24 || minute > 59) return null
        val h = when (period) {
            "凌晨" -> if (hour == 12) 0 else hour
            "清晨", "早上", "上午" -> hour
            "中午" -> if (hour in 1..2) hour + 12 else hour
            "下午", "傍晚" -> if (hour in 1..11) hour + 12 else hour
            "晚上", "夜里" -> if (hour in 1..11) hour + 12 else if (hour == 12) 0 else hour
            else -> if (eveningHint && hour in 1..11) hour + 12 else hour
        }
        if (h > 23) return null
        return LocalTime.of(h, minute)
    }

    /** 解析单个时间词：返回 (小时, 分钟, 时段词?)；识别不了返回 null */
    private fun parseToken(text: String): Triple<Int, Int, String?>? {
        val dian = DIAN_RE.find(text)
        if (dian != null) {
            val h = toNum(dian.groupValues[2]) ?: return null
            val min = when {
                dian.groupValues[3] == "半" -> 30
                dian.groupValues[4].isNotEmpty() -> toNum(dian.groupValues[4]) ?: 0
                else -> 0
            }
            return Triple(h, min, dian.groupValues[1].ifEmpty { null })
        }
        val colon = COLON_RE.find(text) ?: return null
        val h = toNum(colon.groupValues[2]) ?: return null
        val min = colon.groupValues[3].toIntOrNull() ?: return null
        return Triple(h, min, colon.groupValues[1].ifEmpty { null })
    }
}
