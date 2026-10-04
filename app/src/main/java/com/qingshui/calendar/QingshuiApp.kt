package com.qingshui.calendar

import android.app.Application
import com.qingshui.calendar.di.AppContainer
import com.qingshui.calendar.system.DailyImportWorker
import com.qingshui.calendar.system.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 应用入口：初始化依赖容器、通知渠道，并跟随设置维护每日导入任务 */
class QingshuiApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.createChannel(this)

        // 文档导入设置变化 → 重排 / 取消每日后台任务
        appScope.launch {
            container.settingsRepository.settings
                .map { Triple(it.docImportEnabled, it.docUri, it.docHour to it.docMinute) }
                .distinctUntilChanged()
                .collect { (enabled, uri, time) ->
                    if (enabled && uri.isNotBlank()) {
                        DailyImportWorker.schedule(this@QingshuiApp, time.first, time.second)
                    } else {
                        DailyImportWorker.cancel(this@QingshuiApp)
                    }
                }
        }
    }
}
