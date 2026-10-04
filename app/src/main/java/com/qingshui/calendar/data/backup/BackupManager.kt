package com.qingshui.calendar.data.backup

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.qingshui.calendar.data.local.AppDatabase
import com.qingshui.calendar.data.local.entity.EventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JSON 备份 / 恢复（Gson + SAF）。
 * 导出：全部日程 → 用户用系统文件选择器指定的位置。
 * 恢复：同 id 覆盖、新 id 追加（Room REPLACE 策略）。
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase
) {

    private val gson = Gson()

    data class BackupFile(
        val app: String = "QingshuiCalendar",
        val version: Int = 1,
        val exportedAt: Long = System.currentTimeMillis(),
        val events: List<EventEntity> = emptyList()
    )

    /** 导出全部日程，返回导出条数 */
    suspend fun exportTo(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val events = db.eventDao().getAll()
            val json = gson.toJson(BackupFile(events = events))
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("无法写入所选文件")
            events.size
        }
    }

    /** 从备份文件恢复，返回恢复条数 */
    suspend fun restoreFrom(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: error("无法读取所选文件")
            val data = gson.fromJson(text, BackupFile::class.java)
                ?: error("文件内容不是有效的清水日历备份")
            val events = data.events ?: error("备份里没有日程数据")
            if (data.app != "QingshuiCalendar") error("这不是清水日历的备份文件")
            for (e in events) {
                db.eventDao().insert(e) // REPLACE：同 id 覆盖，新 id 追加
            }
            events.size
        }
    }
}
