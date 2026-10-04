package com.qingshui.calendar

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.qingshui.calendar.domain.model.AppSettings
import com.qingshui.calendar.system.NotificationHelper
import com.qingshui.calendar.ui.nav.AppRoot
import com.qingshui.calendar.ui.theme.QingshuiTheme

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as QingshuiApp).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val settings by container.settingsRepository.settings
                .collectAsState(initial = AppSettings())
            QingshuiTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor
            ) {
                AppRoot(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 通知点击带入 EXTRA_EVENT_ID → 转给导航层打开对应日程 */
    private fun handleIntent(intent: Intent?) {
        val eventId = intent?.getLongExtra(NotificationHelper.EXTRA_EVENT_ID, -1L) ?: -1L
        if (eventId > 0) {
            container.pendingEventId.value = eventId
        }
    }
}
