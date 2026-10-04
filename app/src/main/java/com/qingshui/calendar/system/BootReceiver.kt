package com.qingshui.calendar.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qingshui.calendar.QingshuiApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 开机完成 → 重新计算并挂上最近的一次日程提醒 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? QingshuiApp ?: return@launch
                app.container.alarmScheduler.rescheduleNext()
            } catch (e: Exception) {
                // 忽略，下次打开 App 会补跑
            } finally {
                result.finish()
            }
        }
    }
}
