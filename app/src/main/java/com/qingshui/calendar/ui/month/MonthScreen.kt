@file:OptIn(ExperimentalMaterial3Api::class)

package com.qingshui.calendar.ui.month

import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qingshui.calendar.data.local.entity.EventEntity
import com.qingshui.calendar.di.AppContainer
import com.qingshui.calendar.domain.calendar.CalendarUtils
import com.qingshui.calendar.domain.calendar.HolidayCalendar
import com.qingshui.calendar.domain.calendar.LunarCalendar
import com.qingshui.calendar.domain.model.AppSettings
import com.qingshui.calendar.domain.model.EventColors
import com.qingshui.calendar.domain.model.EventOccurrence
import com.qingshui.calendar.domain.repeat.RepeatExpander
import com.qingshui.calendar.domain.model.RepeatType
import com.qingshui.calendar.ui.components.EmptyHint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import androidx.compose.foundation.ExperimentalFoundationApi
import kotlinx.coroutines.ExperimentalCoroutinesApi

// ---------------------------------------------------------------- ViewModel

class MonthViewModel(private val c: AppContainer) : ViewModel() {

    /** 翻页基准月（与屏幕上的 Pager 共用） */
    val baseMonth: YearMonth = YearMonth.now()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    private val _selected = MutableStateFlow<LocalDate?>(LocalDate.now())
    val selected: StateFlow<LocalDate?> = _selected.asStateFlow()

    private val _occurrences = MutableStateFlow<Map<LocalDate, List<EventOccurrence>>>(emptyMap())
    val occurrences: StateFlow<Map<LocalDate, List<EventOccurrence>>> = _occurrences.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    init {
        viewModelScope.launch {
            c.settingsRepository.settings.collect { _settings.value = it }
        }
        viewModelScope.launch {
            combine(
                _month,
                c.settingsRepository.settings.map { it.weekStartMonday }.distinctUntilChanged()
            ) { m, ws -> m to ws }
                .flatMapLatest { (m, ws) ->
                    val gs = CalendarUtils.gridStart(m, ws)
                    c.eventRepository.observeBetween(gs, gs.plusDays(41))
                }
                .collect { list -> _occurrences.value = list.groupBy { it.date } }
        }
        viewModelScope.launch {
            for (d in c.monthFocusChannel) focus(d)
        }
    }

    fun setMonth(m: YearMonth) {
        if (m != _month.value) _month.value = m
    }

    fun select(d: LocalDate) {
        _selected.value = d
    }

    fun goToToday() {
        _month.value = YearMonth.now()
        _selected.value = LocalDate.now()
    }

    /** 年视图跳转过来 */
    fun focus(d: LocalDate) {
        _month.value = YearMonth.from(d)
        _selected.value = d
    }

    fun toggleDone(o: EventOccurrence) {
        viewModelScope.launch {
            val e = o.event
            c.eventRepository.upsert(e.copy(status = if (e.status == 1) 0 else 1))
            c.alarmScheduler.rescheduleNext()
        }
    }
}

// ---------------------------------------------------------------- 屏幕

private const val PAGES = 24000
private const val CENTER = 12000

@Composable
fun MonthScreen(
    factory: ViewModelProvider.Factory,
    onEditEvent: (Long) -> Unit
) {
    val vm: MonthViewModel = viewModel(factory = factory)
    val settings by vm.settings.collectAsState()
    val month by vm.month.collectAsState()
    val occurrences by vm.occurrences.collectAsState()
    val selected by vm.selected.collectAsState()

    val pagerState = rememberPagerState(initialPage = CENTER, pageCount = { PAGES })

    // Pager → VM（用户滑动）
    LaunchedEffect(pagerState.currentPage) {
        vm.setMonth(vm.baseMonth.plusMonths((pagerState.currentPage - CENTER).toLong()))
    }
    // VM → Pager（今天按钮 / 年视图跳月）
    LaunchedEffect(month) {
        val target = CENTER + ChronoUnit.MONTHS.between(vm.baseMonth, month)
        if (target in 0 until PAGES && pagerState.currentPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    var showDaySheet by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // 顶栏：年月 + 翻页 + 今天
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${month.year} 年 ${month.monthValue} 月",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { vm.setMonth(month.minusMonths(1)) }) {
                Text("‹", fontSize = 26.sp)
            }
            TextButton(onClick = { vm.goToToday() }) { Text("今天") }
            IconButton(onClick = { vm.setMonth(month.plusMonths(1)) }) {
                Text("›", fontSize = 26.sp)
            }
        }

        // 月网格（横向翻页）
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { page ->
            val m = vm.baseMonth.plusMonths((page - CENTER).toLong())
            MonthGrid(
                month = m,
                settings = settings,
                today = LocalDate.now(),
                selected = selected,
                occurrences = occurrences,
                onSelect = { vm.select(it); showDaySheet = true }
            )
        }

        // 选中日期的摘要行
        selected?.let { d ->
            val dayEvents = occurrences[d].orEmpty()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${d.monthValue}月${d.dayOfMonth}日 · ${dayEvents.size} 项日程 · 点击查看",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showDaySheet = true }
                )
            }
        }
    }

    if (showDaySheet) {
        val d = selected ?: LocalDate.now()
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showDaySheet = false },
            sheetState = sheetState
        ) {
            DayEventsPanel(
                date = d,
                events = occurrences[d].orEmpty(),
                today = LocalDate.now(),
                onEditEvent = { showDaySheet = false; onEditEvent(it) },
                onToggleDone = { vm.toggleDone(it) }
            )
        }
    }
}

// ---------------------------------------------------------------- 月网格

@Composable
fun MonthGrid(
    month: YearMonth,
    settings: AppSettings,
    today: LocalDate,
    selected: LocalDate?,
    occurrences: Map<LocalDate, List<EventOccurrence>>,
    onSelect: (LocalDate) -> Unit
) {
    val wsMonday = settings.weekStartMonday
    val dates = remember(month, wsMonday) { CalendarUtils.gridDates(month, wsMonday) }
    val labels = remember(wsMonday) { CalendarUtils.weekdayLabels(wsMonday) }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        // 星期表头
        Row(Modifier.fillMaxWidth()) {
            if (settings.showWeekNumber) Spacer(Modifier.width(24.dp))
            labels.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (label == "日" || label == "六") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 6 行 x 7 列
        dates.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().height(56.dp)) {
                if (settings.showWeekNumber) {
                    Text(
                        "${CalendarUtils.weekNumber(week.first(), wsMonday)}",
                        modifier = Modifier.width(24.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                week.forEach { d ->
                    DateCell(
                        date = d,
                        inMonth = d.monthValue == month.monthValue,
                        today = today,
                        selected = selected,
                        events = occurrences[d].orEmpty(),
                        settings = settings,
                        onClick = { onSelect(d) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DateCell(
    date: LocalDate,
    inMonth: Boolean,
    today: LocalDate,
    selected: LocalDate?,
    events: List<EventOccurrence>,
    settings: AppSettings,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isToday = date == today
    val isSelected = date == selected
    val alpha = if (inMonth) 1f else 0.35f

    val lunar = if (settings.showLunar) LunarCalendar.from(date) else null
    val term = if (settings.showSolarTerm) LunarCalendar.solarTermName(date) else null
    val holiday = if (settings.showHoliday) HolidayCalendar.info(date) else null
    val isMakeup = settings.showHoliday && HolidayCalendar.isMakeupWorkday(date)

    val subText: String = when {
        holiday != null && holiday.isOffDay -> holiday.name
        term != null -> term
        lunar != null ->
            if (lunar.day == 1 && !lunar.isLeap) LunarCalendar.monthName(lunar)
            else LunarCalendar.dayName(lunar.day)
        else -> ""
    }
    val subColor = when {
        !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        holiday != null && holiday.isOffDay -> MaterialTheme.colorScheme.error
        term != null -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 日号（今天/选中给圆形底）
        val circleBg = when {
            isToday -> MaterialTheme.colorScheme.primary
            isSelected -> MaterialTheme.colorScheme.secondaryContainer
            else -> androidx.compose.ui.graphics.Color.Transparent
        }
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(circleBg),
            contentAlignment = Alignment.Center
        ) {
            val weekend = date.dayOfWeek.value == 6 || date.dayOfWeek.value == 7
            val numberColor = when {
                isToday -> MaterialTheme.colorScheme.onPrimary
                isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
                !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                weekend -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(
                "${date.dayOfMonth}",
                fontSize = 15.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = numberColor
            )
        }
        // 农历 / 节气 / 节假日小字
        if (subText.isNotEmpty()) {
            Text(
                subText,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                color = subColor.copy(alpha = if (inMonth) 1f else 0.5f)
            )
        } else if (isMakeup) {
            Text(
                "班",
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        // 日程圆点（最多 3 个，按事件颜色）
        val distinct = events.distinctBy { it.event.id }.take(3)
        if (distinct.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                distinct.forEach { o ->
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(
                                androidx.compose.ui.graphics.Color(
                                    EventColors.argb(o.event.color.toLong())
                                ).copy(alpha = if (o.event.status == 1) 0.35f else 1f)
                            )
                    )
                }
            }
        } else {
            Spacer(Modifier.height(5.dp))
        }
    }
}

// ---------------------------------------------------------------- 当天日程面板

@Composable
private fun DayEventsPanel(
    date: LocalDate,
    events: List<EventOccurrence>,
    today: LocalDate,
    onEditEvent: (Long) -> Unit,
    onToggleDone: (EventOccurrence) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            CalendarUtils.dateHeader(date, today),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        if (events.isEmpty()) {
            EmptyHint("这一天没有日程")
            return
        }
        events.forEach { o ->
            EventRow(
                event = o.event,
                occurrenceStart = o.date,
                onClick = { onEditEvent(o.event.id) },
                onToggleDone = { onToggleDone(o) }
            )
        }
    }
}

@Composable
fun EventRow(
    event: EventEntity,
    occurrenceStart: LocalDate,
    onClick: () -> Unit,
    onToggleDone: () -> Unit
) {
    val repoLike = remember { RowTimeFormatter }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = event.status == 1, onCheckedChange = { onToggleDone() })
        Column(Modifier.weight(1f)) {
            Text(
                event.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (event.status == 1) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (event.status == 1) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
            )
            val timeText = if (event.allDay) "全天"
            else RowTimeFormatter.format(event.startTime, event.endTime, occurrenceStart)
            val extra = buildList {
                if (event.repeatType != RepeatType.NONE.name) {
                    add(runCatching { RepeatType.valueOf(event.repeatType).label }.getOrDefault(""))
                }
                if (event.location.isNotBlank()) add(event.location)
            }.filter { it.isNotBlank() }
            Text(
                listOf(timeText, *extra.toTypedArray()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (event.description.isNotBlank()) {
                Text(
                    event.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(androidx.compose.ui.graphics.Color(EventColors.argb(event.color.toLong())))
        )
    }
}

/** 行内时间文案（无仓库依赖的纯函数区） */
private object RowTimeFormatter {
    fun format(startMs: Long, endMs: Long, occurrenceDate: LocalDate): String {
        val start = java.time.Instant.ofEpochMilli(startMs)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        val end = java.time.Instant.ofEpochMilli(endMs)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        val s = String.format("%02d:%02d", start.hour, start.minute)
        val e = String.format("%02d:%02d", end.hour, end.minute)
        // 多天日程：显示「d1 HH:mm 起」
        return if (!occurrenceDate.isEqual(start.toLocalDate())) {
            "$s 起（多天）"
        } else if (end.toLocalTime() == java.time.LocalTime.MIDNIGHT && end.toLocalDate() == start.toLocalDate().plusDays(1)) {
            s
        } else if (end.toLocalDate().isAfter(start.toLocalDate())) {
            "$s 至 ${end.monthValue}/${end.dayOfMonth} $e"
        } else {
            "$s - $e"
        }
    }
}
