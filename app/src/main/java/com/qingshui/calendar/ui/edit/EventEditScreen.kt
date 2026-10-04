@file:OptIn(ExperimentalMaterial3Api::class)

package com.qingshui.calendar.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qingshui.calendar.di.AppContainer
import com.qingshui.calendar.domain.model.EventColors
import com.qingshui.calendar.domain.model.EventDraft
import com.qingshui.calendar.domain.model.EventSource
import com.qingshui.calendar.domain.model.ReminderPresets
import com.qingshui.calendar.domain.model.RepeatType
import com.qingshui.calendar.domain.repeat.RepeatExpander
import com.qingshui.calendar.domain.usecase.draftToEntity
import com.qingshui.calendar.system.NotificationHelper
import com.qingshui.calendar.ui.components.ConfirmDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

// ---------------------------------------------------------------- ViewModel

class EventEditViewModel(private val c: AppContainer, private val editId: Long) : ViewModel() {

    private val _draft = MutableStateFlow<EventDraft?>(null)
    val draft: StateFlow<EventDraft?> = _draft.asStateFlow()

    init {
        viewModelScope.launch {
            val s = c.settingsRepository.settings.first()
            val base: EventDraft? = when {
                editId > 0 -> c.eventRepository.get(editId)?.let { entityToDraft(it) }
                // -2 = 来自一句话预览 / 待处理行的草稿
                editId == -2L -> c.draftChannel.tryReceive().getOrNull()
                else -> null
            }
            _draft.value = base ?: EventDraft(reminderMinutes = s.defaultReminderMinutes)
        }
    }

    fun update(t: (EventDraft) -> EventDraft) {
        _draft.value = _draft.value?.let(t)
    }

    fun save(onDone: () -> Unit) {
        val d = _draft.value ?: return
        viewModelScope.launch {
            val original = d.id?.let { c.eventRepository.get(it) }
            val source = original?.let {
                runCatching { EventSource.valueOf(it.source) }.getOrDefault(EventSource.MANUAL)
            } ?: EventSource.MANUAL
            c.eventRepository.upsert(
                draftToEntity(d, source, existingId = d.id, lineHash = original?.sourceLineHash)
            )
            c.alarmScheduler.rescheduleNext()
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = _draft.value?.id ?: return
        viewModelScope.launch {
            c.eventRepository.delete(id)
            c.alarmScheduler.rescheduleNext()
            onDone()
        }
    }

    private fun entityToDraft(e: com.qingshui.calendar.data.local.entity.EventEntity): EventDraft {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(e.startTime).atZone(zone)
        val end = Instant.ofEpochMilli(e.endTime).atZone(zone)
        return EventDraft(
            id = e.id,
            title = e.title,
            description = e.description,
            date = start.toLocalDate(),
            allDay = e.allDay,
            startTime = if (e.allDay) LocalTime.of(9, 0) else start.toLocalTime(),
            endTime = if (e.allDay) LocalTime.of(10, 0) else end.toLocalTime(),
            location = e.location,
            reminderMinutes = e.reminderMinutes,
            repeat = runCatching { RepeatType.valueOf(e.repeatType) }.getOrDefault(RepeatType.NONE),
            repeatDays = RepeatExpander.parseDays(e.repeatDaysOfWeek),
            tags = e.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            color = e.color,
            status = e.status
        )
    }
}

// ---------------------------------------------------------------- 屏幕

private val WEEK_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

@Composable
fun EventEditScreen(vm: EventEditViewModel, onDone: () -> Unit) {
    val draft by vm.draft.collectAsState()
    val d = draft
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var showDate by remember { mutableStateOf(false) }
    var showStart by remember { mutableStateOf(false) }
    var showEnd by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    var showRepeat by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    var title by remember(d.id) { mutableStateOf(d.title) }
    var description by remember(d.id) { mutableStateOf(d.description) }
    var location by remember(d.id) { mutableStateOf(d.location) }
    var tagsText by remember(d.id) { mutableStateOf(d.tags.joinToString(",")) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                if (d.id != null) "编辑日程" else "新建日程",
                style = MaterialTheme.typography.titleLarge
            )
        }

        OutlinedTextField(
            value = title,
            onValueChange = { title = it; vm.update { dd -> dd.copy(title = it) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text("标题") },
            singleLine = true
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it; vm.update { dd -> dd.copy(description = it) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            label = { Text("备注（可选）") },
            minLines = 2
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("全天", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = d.allDay,
                onCheckedChange = { vm.update { dd -> dd.copy(allDay = it) } }
            )
        }

        FieldRow("日期") {
            Text("${d.date.year}年${d.date.monthValue}月${d.date.dayOfMonth}日 ${WEEK_LABELS[d.date.dayOfWeek.value - 1]}")
        }
        if (!d.allDay) {
            FieldRow("开始时间") {
                Text(String.format("%02d:%02d", d.startTime.hour, d.startTime.minute))
            }
            FieldRow("结束时间") {
                Text(String.format("%02d:%02d", d.endTime.hour, d.endTime.minute))
            }
        }
        OutlinedTextField(
            value = location,
            onValueChange = { location = it; vm.update { dd -> dd.copy(location = it) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            label = { Text("地点（可选）") },
            singleLine = true
        )
        FieldRow("提醒") {
            Text(
                ReminderPresets.labelOf(d.reminderMinutes),
                color = MaterialTheme.colorScheme.primary
            )
        }
        FieldRow("重复") {
            Text(repeatLabel(d.repeat), color = MaterialTheme.colorScheme.primary)
        }
        if (d.repeat == RepeatType.CUSTOM) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                WEEK_LABELS.forEachIndexed { idx, label ->
                    val dow = idx + 1
                    val selectedDow = dow in d.repeatDays
                    Box(
                        Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(CircleShape)
                            .background(
                                if (selectedDow) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable {
                                vm.update { dd ->
                                    val days = dd.repeatDays.toMutableSet()
                                    if (selectedDow) days.remove(dow) else days.add(dow)
                                    dd.copy(repeatDays = days)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label.removePrefix("周"),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selectedDow) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = tagsText,
            onValueChange = {
                tagsText = it
                vm.update { dd ->
                    dd.copy(tags = it.split(',').map { t -> t.trim() }.filter { t -> t.isNotEmpty() })
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            label = { Text("标签（多个用逗号分隔，可选）") },
            singleLine = true
        )

        // 颜色
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EventColors.palette.forEach { c ->
                val argb = EventColors.argb(c)
                val isSelected = d.color == argb
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            width = if (isSelected) 3.dp else 0.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                            shape = CircleShape
                        )
                        .clickable { vm.update { dd -> dd.copy(color = argb) } }
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { vm.save(onDone) },
                enabled = title.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text("保存") }
            if (d.id != null) {
                Button(
                    onClick = { showDelete = true },
                    modifier = Modifier.weight(1f)
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = d.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        vm.update { dd ->
                            dd.copy(date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                    }
                    showDate = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("取消") } }
        ) {
            DatePicker(state = state)
        }
    }
    if (showStart) {
        TimeDialog("开始时间", d.startTime, onDismiss = { showStart = false }) {
            vm.update { dd -> dd.copy(startTime = it) }
            showStart = false
        }
    }
    if (showEnd) {
        TimeDialog("结束时间", d.endTime, onDismiss = { showEnd = false }) {
            vm.update { dd -> dd.copy(endTime = it) }
            showEnd = false
        }
    }
    if (showReminder) {
        RadioDialog(
            title = "提醒",
            options = ReminderPresets.options.map { it.second },
            selectedIndex = ReminderPresets.options.indexOfFirst { it.first == d.reminderMinutes },
            onSelect = { idx ->
                vm.update { dd -> dd.copy(reminderMinutes = ReminderPresets.options[idx].first) }
                showReminder = false
            },
            onDismiss = { showReminder = false }
        )
    }
    if (showRepeat) {
        RadioDialog(
            title = "重复",
            options = RepeatType.entries.map { it.label },
            selectedIndex = RepeatType.entries.indexOf(d.repeat),
            onSelect = { idx ->
                vm.update { dd -> dd.copy(repeat = RepeatType.entries[idx]) }
                showRepeat = false
            },
            onDismiss = { showRepeat = false }
        )
    }
    if (showDelete) {
        ConfirmDialog(
            title = "删除日程",
            text = "确定删除「${d.title}」吗？此操作无法撤销。",
            onConfirm = { showDelete = false; vm.delete(onDone) },
            onDismiss = { showDelete = false }
        )
    }
}

// ---------------------------------------------------------------- 通用小件

@Composable
private fun FieldRow(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        content()
    }
}

@Composable
private fun TimeDialog(
    title: String,
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun RadioDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { idx, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(idx) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = idx == selectedIndex, onClick = { onSelect(idx) })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun repeatLabel(r: RepeatType): String = r.label
