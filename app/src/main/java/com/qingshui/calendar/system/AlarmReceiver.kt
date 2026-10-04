package com.qingshui.calendar.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qingshui.calendar.QingshuiApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 提醒闹钟触发：找到「当初设定的那个提醒时刻」对应的日程并发通知，
 * 然后立刻安排再下一次提醒。setWindow 降级时可能晚几分钟触发，仍能对上号。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? QingshuiApp ?: return@launch
                val container = app.container
                val zone = ZoneId.systemDefault()

                val scheduled = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
                    .getLong("next_trigger", -1L)
                if (scheduled <= 0) return@launch

                val ref = Instant.ofEpochMilli(scheduled - 60_000L)
                    .atZone(zone).toLocalDateTime()
                val fmt = DateTimeFormatter.ofPattern("M月d日 HH:mm")

                for (e in container.eventRepository.getAllOnce()) {
                    if (e.reminderMinutes < 0) continue
                    val pair = container.alarmScheduler.nextReminder(e, ref) ?: continue
                    val triggerMs = pair.first.atZone(zone).toInstant().toEpochMilli()
                    if (triggerMs == scheduled) {
                        val startText = pair.second.format(fmt)
                        val body = buildString {
                            append(if (e.allDay) "$startText（全天）" else startText)
                            if (e.location.isNotBlank()) append(" · ").append(e.location)
                        }
                        NotificationHelper.notifyEvent(context, e.id, e.title, body)
                    }
                }

                // 立即安排再下一次提醒
                container.alarmScheduler.rescheduleNext()
            } catch (e: Exception) {
                // 提醒链路绝不允许崩溃
            } finally {
                result.finish()
            }
        }
    }
}
