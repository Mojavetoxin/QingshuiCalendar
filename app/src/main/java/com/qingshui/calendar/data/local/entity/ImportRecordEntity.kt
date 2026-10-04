package com.qingshui.calendar.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 导入记录表。
 * 去重键 = (sourceUri, lineHash)；lineHash = sha256("uri|规范化后的行内容")。
 * status: 0 导入成功 / 1 待处理（解析失败）/ 2 已忽略。
 */
@Entity(
    tableName = "import_records",
    indices = [Index(value = ["sourceUri", "lineHash"], unique = true)]
)
data class ImportRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceType: String = "DOCUMENT",
    val sourceUri: String,
    val lineHash: String,
    val rawText: String,
    val eventId: Long? = null,
    val status: Int = 0,
    val reason: String = "",
    val processedAt: Long = System.currentTimeMillis()
)
