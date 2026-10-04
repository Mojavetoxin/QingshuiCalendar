package com.qingshui.calendar.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.qingshui.calendar.MainActivity
import com.qingshui.calendar.R

/** 通知工具：渠道创建 + 日程提醒通知（点击打开对应日程） */
object NotificationHelper {

    const val CHANNEL_REMINDERS = "event_reminders"
    const val EXTRA_EVENT_ID = "open_event_id"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_REMINDERS,
            "日程提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "到点的日程提醒通知"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /** 发一条日程提醒；无通知权限时静默失败（不影响主流程） */
    fun notifyEvent(context: Context, eventId: Long, title: String, body: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_EVENT_ID, eventId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            (eventId and 0x7FFFFFFFL).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_calendar)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context)
                .notify((eventId and 0x7FFFFFFFL).toInt(), notification)
        } catch (e: SecurityException) {
            // 用户未授予通知权限，忽略
        }
    }
}
