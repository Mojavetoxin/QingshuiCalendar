package com.qingshui.calendar.di

import android.content.Context
import androidx.room.Room
import com.qingshui.calendar.data.backup.BackupManager
import com.qingshui.calendar.data.local.AppDatabase
import com.qingshui.calendar.data.prefs.SettingsRepository
import com.qingshui.calendar.data.repository.EventRepository
import com.qingshui.calendar.data.repository.ImportRecordRepository
import com.qingshui.calendar.domain.model.EventDraft
import com.qingshui.calendar.domain.usecase.ImportLinesUseCase
import com.qingshui.calendar.system.AlarmScheduler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

/**
 * 手写依赖容器（不引 Hilt，启动更快、依赖更少）。
 * 另外承担三条轻量总线：
 *  - draftChannel：一句话预览 / 待处理行 → 日程编辑页（一次性消费）
 *  - monthFocusChannel：年视图 → 月视图跳月（一次性消费）
 *  - pendingEventId：通知点击 → 导航到对应日程
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val database: AppDatabase =
        Room.databaseBuilder(appContext, AppDatabase::class.java, "qingshui.db").build()

    val settingsRepository = SettingsRepository(appContext)
    val eventRepository = EventRepository(database.eventDao())
    val importRecordRepository = ImportRecordRepository(database.importRecordDao())
    val backupManager = BackupManager(appContext, database)
    val importLines = ImportLinesUseCase(eventRepository, importRecordRepository)
    val alarmScheduler = AlarmScheduler(appContext, eventRepository)

    val draftChannel = Channel<EventDraft>(capacity = 1)
    val monthFocusChannel = Channel<LocalDate>(capacity = 1)
    val pendingEventId = MutableStateFlow<Long?>(null)
}
