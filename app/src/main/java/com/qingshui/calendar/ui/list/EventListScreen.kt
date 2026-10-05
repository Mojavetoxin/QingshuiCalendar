@file:OptIn(ExperimentalMaterial3Api::class)

package com.qingshui.calendar.ui.list

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qingshui.calendar.data.local.entity.EventEntity
import com.qingshui.calendar.di.AppContainer
import com.qingshui.calendar.domain.calendar.CalendarUtils
import com.qingshui.calendar.domain.calendar.LunarCalendar
import com.qingshui.calendar.domain.repeat.RepeatExpander
import com.qingshui.calendar.ui.components.EmptyHint
import com.qingshui.calendar.ui.month.EventRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

// ---------------------------------------------------------------- ViewModel

class EventListViewModel(private val c: AppContainer) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 0 全部 / 1 未完成 / 2 已完成 */
    private val _filter = MutableStateFlow(0)
    val filter: StateFlow<Int> = _filter.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val groups: StateFlow<List<Pair<LocalDate, List<EventEntity>>>> =
        combine(_query, _filter) { q, f -> q to f }
            .flatMapLatest { (q, f) ->
                val src = if (q.isBlank()) c.eventRepository.observeAll()
                else c.eventRepository.search(q)
                src.map { list ->
                    list.filter { e ->
                        when (f) {
                            1 -> e.status == 0
                            2 -> e.status == 1
                            else -> true
                        }
                    }
                }
            }
            .map { list ->
                list.sortedBy { it.startTime }
                    .groupBy { c.eventRepository.dateOf(it.startTime) }
                    .toList()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQuery(q: String) {
        _query.value = q
    }

    fun setFilter(f: Int) {
        _filter.value = f
    }

    fun toggleDone(e: EventEntity) {
        viewModelScope.launch {
            c.eventRepository.upsert(e.copy(status = if (e.status == 1) 0 else 1))
            c.alarmScheduler.rescheduleNext()
        }
    }
}

// ---------------------------------------------------------------- 屏幕

@Composable
fun EventListScreen(
    factory: ViewModelProvider.Factory,
    onEditEvent: (Long) -> Unit
) {
    val vm: EventListViewModel = viewModel(factory = factory)
    val query by vm.query.collectAsState()
    val filter by vm.filter.collectAsState()
    val groups by vm.groups.collectAsState()
    val today = remember { LocalDate.now() }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { vm.setQuery(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("搜索标题 / 标签 / 备注") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "清空")
                    }
                }
            },
            singleLine = true
        )

        Row(Modifier.padding(horizontal = 16.dp)) {
            listOf(0 to "全部", 1 to "未完成", 2 to "已完成").forEach { (f, label) ->
                FilterChip(
                    selected = filter == f,
                    onClick = { vm.setFilter(f) },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        if (groups.isEmpty()) {
            EmptyHint(
                if (query.isBlank()) "还没有日程\n去「月」页点击日期或到「导入」页添加"
                else "没有匹配「$query」的日程"
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                groups.forEach { (date, list) ->
                    item(key = "header_${date.toEpochDay()}") {
                        Text(
                            CalendarUtils.dateHeader(date, today) +
                                " · " + LunarCalendar.ganZhiYear(date.year) + "年",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp)
                        )
                    }
                    items(list, key = { it.id }) { e ->
                        val start = remember(e.id) { vmDate(e) }
                        EventRow(
                            event = e,
                            occurrenceStart = start,
                            onClick = { onEditEvent(e.id) },
                            onToggleDone = { vm.toggleDone(e) }
                        )
                    }
                }
            }
        }
    }
}

private fun vmDate(e: EventEntity): LocalDate =
    java.time.Instant.ofEpochMilli(e.startTime)
        .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
