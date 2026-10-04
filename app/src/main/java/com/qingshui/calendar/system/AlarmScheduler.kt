package com.qingshui.calendar.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.qingshui.calendar.data.local.entity.EventEntity
import com.qingshui.calendar.data.repository.EventRepository
import com.qingshui.calendar.domain.repeat.RepeatExpander
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 提醒调度器：「全 App 只挂一个最近的闹钟」策略。
 * 任意增删改日程 / 开机 / 闹钟触发后，都重算一次最近的提醒时刻并重设闹钟。
 * Android 12+ 没有精确闹钟权限时自动降级为 10 分钟窗口的近似提醒。
 */
class AlarmScheduler(
    private val context: Context,
    private val repo: EventRepository
) {

    companion object {
        private const val REQUEST_CODE = 2001
        private const val PREFS = "alarms"
        private const val KEY_NEXT = "next_trigger"
    }

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 重算最近的下一个提醒并设置闹钟（先取消旧的） */
    suspend fun rescheduleNext() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent())
        prefs().edit().remove(KEY_NEXT).apply()

        val now = LocalDateTime.now(zone)
        var best: LocalDateTime? = null
        for (e in repo.getAllOnce()) {
            val t = nextReminder(e, now)?.first ?: continue
            if (best == null || t.isBefore(best)) best = t
        }
        val trigger = best ?: return
        val millis = trigger.atZone(zone).toInstant().toEpochMilli()
        prefs().edit().putLong(KEY_NEXT, millis).apply()

        val canExact = if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent())
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, millis, 10 * 60 * 1000L, pendingIntent())
        }
    }

    /**
     * 某日程在 ref 之后的第一个提醒时刻。
     * @return Pair(提醒时刻, 日程开始时刻)；没有则 null
     */
    fun nextReminder(e: EventEntity, ref: LocalDateTime): Pair<LocalDateTime, LocalDateTime>? {
        if (e.reminderMinutes < 0) return null
        val timeOfDay = Instant.ofEpochMilli(e.startTime).atZone(zone).toLocalTime()
        val dates = RepeatExpander.occurrenceDates(
            e, ref.toLocalDate(), ref.toLocalDate().plusDays(400), zone
        )
        for (d in dates) {
            val start = d.atTime(timeOfDay)
            val trigger = start.minusMinutes(e.reminderMinutes.toLong())
            if (trigger.isAfter(ref)) return trigger to start
        }
        return null
    }
}
