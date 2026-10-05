package com.qingshui.calendar.ui.settings

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qingshui.calendar.di.AppContainer
import com.qingshui.calendar.domain.model.AppSettings
import com.qingshui.calendar.domain.model.ReminderPresets
import com.qingshui.calendar.ui.components.ConfirmDialog
import com.qingshui.calendar.ui.components.LabeledSwitch
import com.qingshui.calendar.ui.components.LocalAppFeedback
import com.qingshui.calendar.ui.components.SectionTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 设置页：日历显示 / 外观 / 默认提醒 / 数据备份 / 关于 */
class SettingsViewModel(private val c: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        c.settingsRepository.settings
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /** 最近一次操作的结果提示（备份 / 恢复 / 清空） */
    val message = MutableStateFlow("")

    fun setWeekStartMonday(v: Boolean) = viewModelScope.launch { c.settingsRepository.setWeekStartMonday(v) }
    fun setShowLunar(v: Boolean) = viewModelScope.launch { c.settingsRepository.setShowLunar(v) }
    fun setShowSolarTerm(v: Boolean) = viewModelScope.launch { c.settingsRepository.setShowSolarTerm(v) }
    fun setShowHoliday(v: Boolean) = viewModelScope.launch { c.settingsRepository.setShowHoliday(v) }
    fun setShowWeekNumber(v: Boolean) = viewModelScope.launch { c.settingsRepository.setShowWeekNumber(v) }
    fun setThemeMode(v: Int) = viewModelScope.launch { c.settingsRepository.setThemeMode(v) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { c.settingsRepository.setDynamicColor(v) }
    fun setDefaultReminder(minutes: Int) = viewModelScope.launch {
        c.settingsRepository.setDefaultReminderMinutes(minutes)
    }

    fun setSoundEnabled(v: Boolean) = viewModelScope.launch { c.settingsRepository.setSoundEnabled(v) }

    fun setHapticEnabled(v: Boolean) = viewModelScope.launch { c.settingsRepository.setHapticEnabled(v) }

    /** 导出全部日程到用户指定的 JSON 文件 */
    fun export(uri: Uri) = viewModelScope.launch {
        c.backupManager.exportTo(uri)
            .onSuccess { message.value = "已导出 $it 条日程" }
            .onFailure { message.value = "导出失败：${it.message ?: "未知错误"}" }
    }

    /** 从 JSON 备份恢复（同 id 覆盖、新 id 追加） */
    fun restore(uri: Uri) = viewModelScope.launch {
        c.backupManager.restoreFrom(uri)
            .onSuccess {
                message.value = "已恢复 $it 条日程"
                c.alarmScheduler.rescheduleNext()
            }
            .onFailure { message.value = "恢复失败：${it.message ?: "未知错误"}" }
    }

    /** 清空全部日程与导入记录（不可恢复） */
    fun clearAll() = viewModelScope.launch {
        c.eventRepository.deleteAll()
        c.importRecordRepository.deleteAll()
        c.alarmScheduler.rescheduleNext()
        message.value = "已清空全部日程数据"
    }
}

@Composable
fun SettingsScreen(factory: ViewModelProvider.Factory) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val s by vm.settings.collectAsState()
    val message by vm.message.collectAsState()

    val fx = LocalAppFeedback.current
    var showReminderDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { vm.export(it) } }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.restore(it) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        // ── 日历显示 ──
        SectionTitle("日历显示")
        LabeledSwitch(
            title = "一周从周一开始",
            checked = s.weekStartMonday,
            onChange = { vm.setWeekStartMonday(it) },
            subtitle = "关闭后一周从周日开始"
        )
        LabeledSwitch(
            title = "显示农历",
            checked = s.showLunar,
            onChange = { vm.setShowLunar(it) }
        )
        LabeledSwitch(
            title = "显示节气",
            checked = s.showSolarTerm,
            onChange = { vm.setShowSolarTerm(it) }
        )
        LabeledSwitch(
            title = "显示法定节假日与调休",
            checked = s.showHoliday,
            onChange = { vm.setShowHoliday(it) },
            subtitle = "2027 年及以后为按历法规则的推算，仅供参考"
        )
        LabeledSwitch(
            title = "显示周数",
            checked = s.showWeekNumber,
            onChange = { vm.setShowWeekNumber(it) }
        )

        // ── 外观 ──
        SectionTitle("外观")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEach { (mode, label) ->
                FilterChip(
                    selected = s.themeMode == mode,
                    onClick = { fx.tick(); vm.setThemeMode(mode) },
                    label = { Text(label) }
                )
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            LabeledSwitch(
                title = "动态取色",
                checked = s.dynamicColor,
                onChange = { vm.setDynamicColor(it) },
                subtitle = "跟随系统壁纸配色（Android 12+）"
            )
        }

        // ── 提醒 ──
        SectionTitle("提醒")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { fx.tick(); showReminderDialog = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("新建日程的默认提醒", modifier = Modifier.weight(1f))
            Text(
                ReminderPresets.labelOf(s.defaultReminderMinutes),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }

        // ── 声音与触感 ──
        SectionTitle("声音与触感")
        LabeledSwitch(
            title = "音效",
            checked = s.soundEnabled,
            onChange = { vm.setSoundEnabled(it) },
            subtitle = "点击、翻页、保存时的系统提示音"
        )
        LabeledSwitch(
            title = "触感反馈",
            checked = s.hapticEnabled,
            onChange = { vm.setHapticEnabled(it) },
            subtitle = "轻点、确认、删除时的手感振动"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { fx.confirm() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("试一下手感", modifier = Modifier.weight(1f))
            Text(
                "点这里",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            "音效与触感由系统统一管理：若你在系统设置里关掉了「触摸提示音」或「触感反馈」，这里即使打开也不会有声音或振动。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        // ── 数据 ──
        SectionTitle("数据")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { fx.confirm(); exportLauncher.launch("qingshui-backup.json") }) {
                Text("导出备份")
            }
            OutlinedButton(onClick = {
                fx.confirm()
                restoreLauncher.launch(arrayOf("application/json", "text/*"))
            }) {
                Text("恢复备份")
            }
        }
        Button(
            onClick = { fx.warn(); showClearDialog = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text("清空全部日程")
        }
        Text(
            "备份为 JSON 文件，可用于换机或存档；恢复时相同编号的日程会被覆盖。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        if (message.isNotEmpty()) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // ── 关于 ──
        SectionTitle("关于")
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("清水日历 v1.0.0", fontWeight = FontWeight.Medium)
            Text(
                "一个本地优先的离线日历：不申请网络权限，所有数据只保存在你的手机上。",
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "农历、节气依据通用历法表推算；法定节假日与调休数据覆盖 2026—2030 年，之后的年份按「周一到周五为工作日、周末为休息日」的规则推算，仅供参考，具体以国务院办公厅发布为准。",
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    if (showReminderDialog) {
        AlertDialog(
            onDismissRequest = { showReminderDialog = false },
            title = { Text("默认提醒") },
            text = {
                Column {
                    ReminderPresets.options.forEach { (minutes, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    fx.tick()
                                    vm.setDefaultReminder(minutes)
                                    showReminderDialog = false
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = s.defaultReminderMinutes == minutes,
                                onClick = {
                                    vm.setDefaultReminder(minutes)
                                    showReminderDialog = false
                                }
                            )
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showReminderDialog = false }) { Text("取消") }
            }
        )
    }

    if (showClearDialog) {
        ConfirmDialog(
            title = "清空全部日程",
            text = "将删除所有日程与导入记录，且无法恢复。备份了吗？",
            confirmText = "清空",
            onConfirm = {
                vm.clearAll()
                showClearDialog = false
            },
            onDismiss = { showClearDialog = false }
        )
    }
}
