package com.qingshui.calendar.domain.model

/** 重复类型 */
enum class RepeatType(val label: String) {
    NONE("不重复"),
    DAILY("每天"),
    WEEKLY("每周"),
    MONTHLY("每月"),
    YEARLY("每年"),
    WEEKDAY("工作日"),
    CUSTOM("自定义周几")
}

/** 日程来源 */
enum class EventSource(val label: String) {
    MANUAL("手动添加"),
    QUICK_ADD("一句话导入"),
    DOCUMENT("文档导入")
}

/** 预设提醒选项（单位：分钟） */
object ReminderPresets {
    val options: List<Pair<Int, String>> = listOf(
        -1 to "不提醒",
        0 to "到点时",
        5 to "提前 5 分钟",
        10 to "提前 10 分钟",
        15 to "提前 15 分钟",
        30 to "提前 30 分钟",
        60 to "提前 1 小时",
        1440 to "提前 1 天"
    )

    fun labelOf(minutes: Int): String =
        options.firstOrNull { it.first == minutes }?.second ?: "提前 $minutes 分钟"
}

/** 日程预设颜色（ARGB） */
object EventColors {
    val palette: List<Long> = listOf(
        0xFF2A9D8FL, // 清水青
        0xFF264653L, // 墨蓝
        0xFFE76F51L, // 砖红
        0xFFF4A261L, // 杏黄
        0xFFE9C46AL, // 沙金
        0xFF6A994EL, // 橄榄绿
        0xFF577590L, // 雾蓝
        0xFF9D4EDDL  // 紫藤
    )

    fun default(): Int = 0xFF2A9D8F.toInt()

    /** 按已有日程条数轮换取色：新建的日程默认就是彩色的，省掉一步手选 */
    fun nextColor(existingCount: Int): Int {
        val n = palette.size
        return palette[((existingCount % n) + n) % n].toInt()
    }

    fun argb(longValue: Long): Int = longValue.toInt()
}
