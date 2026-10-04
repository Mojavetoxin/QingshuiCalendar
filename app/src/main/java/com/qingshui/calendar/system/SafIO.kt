package com.qingshui.calendar.system

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** SAF 文档读写小工具（读文本 / 查文件名） */
object SafIO {

    /** 读取 content:// 文本文件全部内容；失败返回 null */
    fun readText(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull()

    /** 查询文档显示名；失败返回 null */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
