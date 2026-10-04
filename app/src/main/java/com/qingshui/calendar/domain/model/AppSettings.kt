package com.qingshui.calendar.domain.model

/**
 * 应用设置（DataStore 持久化）。
 * 文档导入的相关设置（docUri / 自动导入开关 / 读取时间）也放在这里，
 * UI 上在「导入」页管理。
 */
data class AppSettings(
    val weekStartMonday: Boolean = true,   // 周起始日：true=周一 false=周日
    val showLunar: Boolean = true,         // 显示农历
    val showSolarTerm: Boolean = true,     // 显示节气
    val showHoliday: Boolean = true,       // 显示节假日/调休
    val showWeekNumber: Boolean = false,   // 显示周数
    val themeMode: Int = 0,                // 0 跟随系统 / 1 浅色 / 2 深色
    val dynamicColor: Boolean = true,      // Android 12+ 动态取色
    val defaultReminderMinutes: Int = 15,  // 默认提醒
    // —— 文档每日导入 ——
    val docImportEnabled: Boolean = false,
    val docUri: String = "",               // SAF content:// URI
    val docName: String = "",              // 展示用文件名
    val docHour: Int = 7,
    val docMinute: Int = 30,
    val notifPermissionAsked: Boolean = false
)
