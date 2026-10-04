package com.qingshui.calendar.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 日程表。
 * 时间统一存 epoch 毫秒；全天日程 startTime=当天 00:00，endTime=次日 00:00。
 * 重复日程不预生成实例，只存规则（见 RepeatExpander）。
 */
@Entity(
    tableName = "events",
    indices = [
        Index(value = ["startTime"]),
        Index(value = ["sourceLineHash"])
    ]
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val startTime: Long,
    val endTime: Long,
    val allDay: Boolean = false,
    val location: String = "",
    val reminderMinutes: Int = -1,      // -1 不提醒 / 0 到点 / >0 提前 N 分钟
    val repeatType: String = "NONE",    // RepeatType 枚举名
    val repeatInterval: Int = 1,        // 每 N 天/周/月/年
    val repeatDaysOfWeek: String = "",  // CUSTOM 用：ISO 星期编号 CSV，如 "1,3,5"
    val repeatEndTime: Long? = null,    // 重复截止时刻；null = 永久
    val tags: String = "",              // 标签 CSV
    val color: Int = 0xFF2A9D8F.toInt(),
    val status: Int = 0,                // 0 未完成 / 1 已完成
    val source: String = "MANUAL",      // EventSource 枚举名
    val sourceLineHash: String? = null, // 文档导入去重键
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
