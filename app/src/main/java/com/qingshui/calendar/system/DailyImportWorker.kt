package com.qingshui.calendar.system

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.qingshui.calendar.QingshuiApp
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * 每日文档导入后台任务：到设定时刻读取所选 txt/md 文档，
 * 逐行解析、去重（行 hash + 来源 Uri）、入库；失败行进待处理列表。
 */
class DailyImportWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? QingshuiApp ?: return Result.success()
        val c = app.container
        val s = c.settingsRepository.settings.first()
        if (!s.docImportEnabled || s.docUri.isBlank()) return Result.success()

        val text = SafIO.readText(applicationContext, Uri.parse(s.docUri))
            ?: return Result.success() // 文档暂时不可读（被移动/授权失效），不重试轰炸
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return Result.success()

        val results = c.importLines.run(s.docUri, lines)
        if (results.any { it.eventId != null }) {
            c.alarmScheduler.rescheduleNext()
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "daily_doc_import"

        /** 按 settings 里的时刻注册每天一次的导入任务（重复调用幂等，用 UPDATE 策略） */
        fun schedule(context: Context, hour: Int, minute: Int) {
            val now = LocalDateTime.now()
            var target = LocalDateTime.of(LocalDate.now(), LocalTime.of(hour, minute))
            if (!target.isAfter(now)) target = target.plusDays(1)
            val request = PeriodicWorkRequestBuilder<DailyImportWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, target))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
