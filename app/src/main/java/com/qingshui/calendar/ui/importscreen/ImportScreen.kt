package com.qingshui.calendar.ui.importscreen

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.qingshui.calendar.data.local.entity.ImportRecordEntity
import com.qingshui.calendar.di.AppContainer
import com.qingshui.calendar.domain.model.AppSettings
import com.qingshui.calendar.domain.model.EventColors
import com.qingshui.calendar.domain.model.EventDraft
import com.qingshui.calendar.domain.model.EventSource
import com.qingshui.calendar.domain.model.QuickAddResult
import com.qingshui.calendar.domain.model.ReminderPresets
import com.qingshui.calendar.domain.model.RepeatType
import com.qingshui.calendar.domain.parse.NaturalLanguageParser
import com.qingshui.calendar.domain.usecase.ImportLinesUseCase
import com.qingshui.calendar.domain.usecase.draftToEntity
import com.qingshui.calendar.domain.usecase.quickAddToDraft
import com.qingshui.calendar.system.DailyImportWorker
import com.qingshui.calendar.system.SafIO
import com.qingshui.calendar.ui.components.LocalAppFeedback
import com.qingshui.calendar.ui.components.SectionTitle
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 导入页：一句话解析 + 文档每日导入 + 待处理行管理 */
class ImportViewModel(private val c: AppContainer) : ViewModel() {

    val appContext: Context = c.appContext

    val quickText = MutableStateFlow("")
    val quickResult = MutableStateFlow<QuickAddResult?>(null)
    val busy = MutableStateFlow(false)
    val summary = MutableStateFlow("")

    val settings: StateFlow<AppSettings> =
        c.settingsRepository.settings
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val pending: StateFlow<List<ImportRecordEntity>> =
        c.importRecordRepository.observePending()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 解析一句话（不落库，先出预览） */
    fun parseQuick() {
        val text = quickText.value.trim()
        if (text.isEmpty()) return
        quickResult.value = NaturalLanguageParser.parse(text)
    }

    fun clearQuick() {
        quickText.value = ""
        quickResult.value = null
    }

    /** 保存一句话预览为日程 */
    fun saveQuick(onDone: () -> Unit) {
        val r = quickResult.value ?: return
        viewModelScope.launch {
            val defaultReminder = c.settingsRepository.settings.first().defaultReminderMinutes
            val draft = quickAddToDraft(r, defaultReminder).copy(
                color = EventColors.nextColor(c.eventRepository.getAllOnce().size)
            )
            c.eventRepository.upsert(draftToEntity(draft, EventSource.QUICK_ADD))
            c.alarmScheduler.rescheduleNext()
            clearQuick()
            onDone()
        }
    }

    /** 把一句话预览转成草稿送进编辑页，返回是否成功送出 */
    fun quickToEdit(): Boolean {
        val r = quickResult.value ?: return false
        val dr = settings.value.defaultReminderMinutes
        return c.draftChannel.trySend(quickAddToDraft(r, dr)).isSuccess
    }

    /** SAF 选中文档后：持久化授权 + 写入设置 + 注册每日任务 */
    fun onDocPicked(uri: Uri, name: String?) = viewModelScope.launch {
        runCatching {
            c.appContext.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        c.settingsRepository.setDocImport(true, uri.toString(), name ?: "已选文档")
        val s = c.settingsRepository.settings.first()
        DailyImportWorker.schedule(c.appContext, s.docHour, s.docMinute)
    }

    fun setAutoImport(enabled: Boolean) = viewModelScope.launch {
        c.settingsRepository.setDocImportEnabled(enabled)
        if (enabled) {
            val s = c.settingsRepository.settings.first()
            if (s.docUri.isNotBlank()) DailyImportWorker.schedule(c.appContext, s.docHour, s.docMinute)
        } else {
            DailyImportWorker.cancel(c.appContext)
        }
    }

    fun setDocTime(hour: Int, minute: Int) = viewModelScope.launch {
        c.settingsRepository.setDocTime(hour, minute)
        val s = c.settingsRepository.settings.first()
        if (s.docImportEnabled && s.docUri.isNotBlank()) {
            DailyImportWorker.schedule(c.appContext, hour, minute)
        }
    }

    /** 立即读取文档并导入一遍 */
    fun importNow() {
        viewModelScope.launch {
            busy.value = true
            try {
                val s = c.settingsRepository.settings.first()
                if (s.docUri.isBlank()) {
                    summary.value = "还没有选择文档"
                    return@launch
                }
                val text = SafIO.readText(c.appContext, Uri.parse(s.docUri))
                if (text == null) {
                    summary.value = "文档暂时读不了（可能被移动或授权已失效）"
                    return@launch
                }
                val lines = text.lines().filter { it.isNotBlank() }
                if (lines.isEmpty()) {
                    summary.value = "文档里没有可用内容"
                    return@launch
                }
                val rs = c.importLines.run(s.docUri, lines)
                val ok = rs.count { it.status == ImportLinesUseCase.STATUS_OK }
                val dup = rs.count { it.status == ImportLinesUseCase.STATUS_DUPLICATE }
                val pend = rs.count { it.status == ImportLinesUseCase.STATUS_PENDING }
                summary.value = "本次读取：新增 $ok 条 · 跳过重复 $dup 条 · 待处理 $pend 条"
                if (ok > 0) c.alarmScheduler.rescheduleNext()
            } catch (t: Throwable) {
                summary.value = "读取失败：${t.message ?: "未知错误"}"
            } finally {
                busy.value = false
            }
        }
    }

    /** 重试一条待处理记录 */
    fun retryPending(rec: ImportRecordEntity) = viewModelScope.launch {
        busy.value = true
        try {
            val rs = c.importLines.run(rec.sourceUri, listOf(rec.rawText))
            if (rs.any { it.eventId != null }) c.alarmScheduler.rescheduleNext()
        } finally {
            busy.value = false
        }
    }

    /** 待处理行 → 编辑页手动补全（原记录标记为忽略） */
    fun editPending(rec: ImportRecordEntity): Boolean {
        val draft = EventDraft(
            title = rec.rawText.take(40),
            description = "原文：${rec.rawText}"
        )
        val sent = c.draftChannel.trySend(draft).isSuccess
        if (sent) {
            viewModelScope.launch { c.importRecordRepository.markIgnored(rec) }
        }
        return sent
    }

    fun ignorePending(rec: ImportRecordEntity) = viewModelScope.launch {
        c.importRecordRepository.markIgnored(rec)
    }

    fun deletePending(rec: ImportRecordEntity) = viewModelScope.launch {
        c.importRecordRepository.delete(rec.id)
    }
}

private val DATE_FMT = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    factory: ViewModelProvider.Factory,
    onEditEvent: (Long) -> Unit
) {
    val vm: ImportViewModel = viewModel(factory = factory)
    val quickText by vm.quickText.collectAsState()
    val quickResult by vm.quickResult.collectAsState()
    val busy by vm.busy.collectAsState()
    val summary by vm.summary.collectAsState()
    val pending by vm.pending.collectAsState()
    val settings by vm.settings.collectAsState()

    val fx = LocalAppFeedback.current
    var showTimePicker by remember { mutableStateOf(false) }

    // 一句话解析失败 → 一声"不行"；文档导入有结果 → 成功/失败各一声
    LaunchedEffect(quickResult) {
        val r = quickResult
        if (r != null && !r.ok) fx.error()
    }
    LaunchedEffect(summary) {
        if (summary.isNotBlank()) {
            if (summary.contains("失败")) fx.error() else fx.success()
        }
    }

    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.onDocPicked(uri, SafIO.displayName(vm.appContext, uri))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── 一句话导入 ──
        SectionTitle("一句话导入")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = quickText,
                    onValueChange = { vm.quickText.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例如：明天下午3点 在教室 开班会") },
                    trailingIcon = {
                        if (quickText.isNotEmpty()) {
                            IconButton(onClick = { fx.tick(); vm.clearQuick() }) {
                                Icon(Icons.Filled.Close, contentDescription = "清空")
                            }
                        }
                    },
                    maxLines = 3
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { fx.select(); vm.parseQuick() }, enabled = quickText.isNotBlank()) {
                        Text("解析")
                    }
                }

                quickResult?.let { r ->
                    QuickPreviewCard(
                        result = r,
                        onSave = { fx.confirm(); vm.saveQuick { } },
                        onEdit = { fx.tick(); if (vm.quickToEdit()) onEditEvent(-2L) }
                    )
                }
            }
        }

        // ── 文档每日导入 ──
        SectionTitle("文档导入")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "选择一个 txt / md 文档，内容每行一条日程。App 会每天在设定时刻自动读取并导入新增行；同一行内容不会重复导入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { fx.tick(); docPicker.launch(arrayOf("text/*", "application/json")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("选择文档")
                }
                Text(
                    "文档：${settings.docName.ifEmpty { "未选择" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("自动每天读取", modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.docImportEnabled,
                        onCheckedChange = { vm.setAutoImport(it) }
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { fx.tick(); showTimePicker = true }
                        .padding(vertical = 4.dp)
                ) {
                    Text("每天读取时间", modifier = Modifier.weight(1f))
                    Text(
                        "%02d:%02d".format(settings.docHour, settings.docMinute),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(onClick = { fx.select(); vm.importNow() }, enabled = !busy) {
                        Text("立即读取")
                    }
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
                if (summary.isNotEmpty()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // ── 待处理（解析失败的行） ──
        if (pending.isNotEmpty()) {
            SectionTitle("待处理（${pending.size}）")
            pending.forEach { rec ->
                PendingCard(
                    rec = rec,
                    busy = busy,
                    onRetry = { fx.select(); vm.retryPending(rec) },
                    onEdit = { fx.tick(); if (vm.editPending(rec)) onEditEvent(-2L) },
                    onIgnore = { fx.tick(); vm.ignorePending(rec) },
                    onDelete = { fx.warn(); vm.deletePending(rec) }
                )
            }
        }

        // ── 语法速查 ──
        SectionTitle("解析语法速查")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "「明天下午3点 开会」→ 明天 15:00",
                    "「周五晚上8点 健身 到9点半」→ 最近周五 20:00-21:30",
                    "「每周一 交周报」→ 每周重复",
                    "「下个月5号 交房租」→ 下月5日",
                    "「3天后 复习」→ 3天后",
                    "「6月1日 全天 儿童节」→ 当天全天",
                    "「#工作 明天9点到12点 复习」→ 打标签「工作」",
                    "「明天10点 提前半小时提醒 取快递」→ 10:00，提前 30 分钟",
                    "没有日期时间的行会进「待处理」，可手动补全"
                ).forEach { line ->
                    Text(line, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(
            initialHour = settings.docHour,
            initialMinute = settings.docMinute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    vm.setDocTime(state.hour, state.minute)
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            },
            text = { TimePicker(state) }
        )
    }
}

/** 一句话解析结果预览卡 */
@Composable
private fun QuickPreviewCard(
    result: QuickAddResult,
    onSave: () -> Unit,
    onEdit: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (result.ok) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                PreviewRow("标题", result.title)
                result.date?.let { PreviewRow("日期", it.format(DATE_FMT)) }
                PreviewRow(
                    "时间",
                    result.time?.let { t ->
                        result.endTime?.let { e -> "$t-$e" } ?: t.toString()
                    } ?: "全天"
                )
                if (result.repeat != RepeatType.NONE) {
                    val days = if (result.repeat == RepeatType.CUSTOM) {
                        "周" + result.repeatDays.sorted().map { "一二三四五六日"[it - 1] }
                            .joinToString("")
                    } else ""
                    PreviewRow("重复", result.repeat.label + days)
                }
                PreviewRow(
                    "提醒",
                    result.reminderMinutes?.let { ReminderPresets.labelOf(it) } ?: "用默认设置"
                )
                if (result.tags.isNotEmpty()) PreviewRow("标签", result.tags.joinToString("、"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave) { Text("保存") }
                OutlinedButton(onClick = onEdit) { Text("修改后再存") }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    "没能解析出来：${result.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    "原文：「${result.raw}」。可以换种说法，或点下面按钮手动填写。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Button(onClick = onEdit) { Text("手动填写") }
        }
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Row {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp)
        )
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** 待处理行卡片 */
@Composable
private fun PendingCard(
    rec: ImportRecordEntity,
    busy: Boolean,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onIgnore: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                rec.rawText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (rec.reason.isNotBlank()) {
                Text(
                    "原因：${rec.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRetry, enabled = !busy) { Text("重试") }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onIgnore) { Text("忽略") }
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
