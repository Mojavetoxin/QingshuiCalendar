@file:OptIn(ExperimentalMaterial3Api::class)

package com.qingshui.calendar.ui.month

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
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
import com.qingshui.calendar.domain.model.RepeatType
import com.qingshui.calendar.domain.repeat.RepeatExpander
import com.qingshui.calendar.ui.components.EmptyHint
import com.qingshui.calendar.ui.components.LocalAppFeedback
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- ViewModel

@OptIn(ExperimentalCoroutinesApi::class)
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
    val month by vm.month.collectAsState()
    // ★ 这三项不取成"值"，而是持有 State 对象、在真正用到的地方才读 .value。
    //   Pager 的页面跑在子组合里，闭包捕获的值不会让页面订阅状态 → 页面会停在首次组合的数据。
    val settingsState = vm.settings.collectAsState()
    val occurrencesState = vm.occurrences.collectAsState()
    val selectedState = vm.selected.collectAsState()
    val selected = selectedState.value

    val pagerState = rememberPagerState(initialPage = CENTER, pageCount = { PAGES })

    // Pager → VM（用户滑动）
    LaunchedEffect(pagerState.currentPage) {
        vm.setMonth(vm.baseMonth.plusMonths((pagerState.currentPage - CENTER).toLong()))
    }
    // VM → Pager（今天按钮 / 年视图跳月）
    LaunchedEffect(month) {
        val target = CENTER + ChronoUnit.MONTHS.between(vm.baseMonth, month).toInt()
        if (target in 0 until PAGES && pagerState.currentPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    var showDaySheet by remember { mutableStateOf(false) }
    // 下拉放大：false = 整月六行，true = 只显示选中日所在的一周（放大）
    var zoomed by remember { mutableStateOf(false) }
    var dragAcc by remember { mutableStateOf(0f) }

    val feedback = LocalAppFeedback.current

    // 把网格要用的动态数据打包成一个 State，在 Pager 页面内部读它 → 页面才会订阅并重组
    val gridInput by remember {
        derivedStateOf {
            MonthGridInput(
                settings = settingsState.value,
                selected = selectedState.value,
                occurrences = occurrencesState.value,
                zoomed = zoomed
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .pointerInput(zoomed) {
                detectVerticalDragGestures(
                    onDragEnd = { dragAcc = 0f },
                    onDragCancel = { dragAcc = 0f }
                ) { _, dragAmount ->
                    dragAcc += dragAmount
                    if (!zoomed && dragAcc > 50f) {
                        feedback.select(); zoomed = true; dragAcc = 0f
                    } else if (zoomed && dragAcc < -50f) {
                        feedback.tick(); zoomed = false; dragAcc = 0f
                    }
                }
            }
    ) {
        // 顶栏：左右翻页 + 居中标题（公历 + 干支农历）+ 今天
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { feedback.page(false); vm.setMonth(month.minusMonths(1)) }) {
                Text("‹", fontSize = 26.sp)
            }
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "${month.year} 年 ${month.monthValue} 月",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    LunarCalendar.ganZhiYear(month.year) + "年" +
                        LunarCalendar.ganZhiMonth(
                            month.year,
                            LunarCalendar.from(month.atDay(1))?.month ?: 1
                        ) + "月",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { feedback.select(); vm.goToToday() }) { Text("今天") }
            IconButton(onClick = { feedback.page(true); vm.setMonth(month.plusMonths(1)) }) {
                Text("›", fontSize = 26.sp)
            }
        }

        // 月网格（横向翻页）：下拉放大成周视图，上滑收回整月
        // 固定高度 = 表头 + 行数 × 行高（周视图行更高，显示当天日程标题）
        val gridHeight by animateDpAsState(
            targetValue = if (zoomed) 134.dp else 400.dp,
            label = "gridHeight"
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeight)
        ) { page ->
            val m = vm.baseMonth.plusMonths((page - CENTER).toLong())
            MonthGrid(
                month = m,
                input = gridInput,
                today = LocalDate.now(),
                onSelect = { feedback.tick(); vm.select(it) }
            )
        }

        HorizontalDivider(
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )

        // 选中日期起的日程：连续列出后续几天，可以直接看到未来安排
        val selDay = selected
        if (selDay == null) {
            EmptyHint("点一个日期查看当天日程")
        } else {
            val upcoming = occurrencesState.value
                .filterKeys { !it.isBefore(selDay) }.toSortedMap()
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                if (upcoming.isEmpty()) {
                    Text(
                        "这一天和之后还没有日程 —— 点右下角按钮新建",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 12.dp)
                    )
                } else {
                    upcoming.forEach { entry ->
                        val d = entry.key
                        Text(
                            text = CalendarUtils.dateHeader(d, LocalDate.now()) +
                                " · " + LunarCalendar.ganZhiYear(d.year) + "年" +
                                LunarCalendar.lunarMonthDay(d) +
                                " · " + LunarCalendar.ganZhiDay(d) + "日",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)
                        )
                        entry.value.forEach { o ->
                            EventRow(
                                event = o.event,
                                occurrenceStart = o.date,
                                onClick = { onEditEvent(o.event.id) },
                                onToggleDone = { vm.toggleDone(o) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(76.dp))
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
                events = occurrencesState.value[d].orEmpty(),
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
    input: MonthGridInput,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit
) {
    val settings = input.settings
    val wsMonday = settings.weekStartMonday
    val dates = remember(month, wsMonday) { CalendarUtils.gridDates(month, wsMonday) }
    val labels = remember(wsMonday) { CalendarUtils.weekdayLabels(wsMonday) }
    // 周视图：只保留包含选中日（或今天）的那一行
    val allWeeks = remember(dates) { dates.chunked(7) }
    val weeks = remember(allWeeks, input.selected, today, input.zoomed) {
        if (!input.zoomed) allWeeks
        else {
            val anchor = input.selected ?: today
            val idx = allWeeks.indexOfFirst { w -> w.any { it == anchor } }
            listOf(allWeeks[if (idx >= 0) idx else 0])
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
        // 星期表头（13sp -> 14sp）
        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
            if (settings.showWeekNumber) Spacer(Modifier.width(24.dp))
            labels.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (label == "日" || label == "六") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 6 行 x 7 列（周视图只渲染一行，行高更大并显示当天日程标题）
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth().height(if (input.zoomed) 104.dp else 62.dp)) {
                if (settings.showWeekNumber) {
                    Text(
                        "${CalendarUtils.weekNumber(week.first(), wsMonday)}",
                        modifier = Modifier.width(24.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                week.forEach { d ->
                    DateCell(
                        date = d,
                        detail = input.zoomed,
                        inMonth = d.monthValue == month.monthValue,
                        today = today,
                        selected = input.selected,
                        events = input.occurrences[d].orEmpty(),
                        settings = settings,
                        onClick = { onSelect(d) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * 月网格的全部动态输入。
 *
 * ★ 为什么要打成一个对象、并且由外部以 State 的形式传入？
 * `HorizontalPager` 的每一页是在**子组合**里渲染的。如果页面闭包直接捕获
 * `occurrences` 这类"读出来的值"，页面就不会订阅这些状态——数据变了页面也不重组，
 * 于是日程圆点、选中高亮永远停在首次组合时的样子（首次组合时 occurrences 还是空 map）。
 * 实测症状：列表（普通 Column）正常更新，网格里的圆点却一个都不出现。
 * 修法：把输入打包成 State，在页面内部读 `.value`，页面才会订阅并在变化时重组。
 */
data class MonthGridInput(
    val settings: AppSettings,
    val selected: LocalDate?,
    val occurrences: Map<LocalDate, List<EventOccurrence>>,
    val zoomed: Boolean
)

@Composable
private fun DateCell(
    date: LocalDate,
    detail: Boolean = false,
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
    val weekend = date.dayOfWeek.value == 6 || date.dayOfWeek.value == 7

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
        !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        holiday != null && holiday.isOffDay -> MaterialTheme.colorScheme.error
        term != null -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 日号：今天/选中给「圆角方块」底（比圆形更接近系统日历的观感）
        val chipBg = when {
            isToday -> MaterialTheme.colorScheme.primary
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            else -> androidx.compose.ui.graphics.Color.Transparent
        }
        val numberColor = when {
            isToday -> MaterialTheme.colorScheme.onPrimary
            isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
            !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
            weekend -> MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
            else -> MaterialTheme.colorScheme.onSurface
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(chipBg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${date.dayOfMonth}",
                fontSize = 18.sp,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Medium,
                color = numberColor
            )
        }
        // 农历 / 节气 / 节假日（字号从 8sp 提到 10.5sp）
        Text(
            subText.ifEmpty { " " },
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            color = subColor
        )
        // 固定高度的一行：周视图里显示当天首条日程标题，否则显示彩色圆点。
        // 无论有没有日程都占同样高度，翻月时不会跳。
        Box(
            Modifier.height(10.dp).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (detail && events.isNotEmpty()) {
                Text(
                    events.first().event.title,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val dots = events.distinctBy { it.event.id }.take(3)
                if (dots.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        dots.forEach { o ->
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        androidx.compose.ui.graphics.Color(
                                            EventColors.argb(o.event.color.toLong())
                                        ).copy(alpha = if (o.event.status == 1) 0.35f else 1f)
                                    )
                            )
                        }
                    }
                }
            }
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
    val fx = LocalAppFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                RoundedCornerShape(12.dp)
            )
            .clickable { fx.tick(); onClick() }
            .padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = event.status == 1,
            onCheckedChange = { checked ->
                if (checked) fx.confirm() else fx.tick()
                onToggleDone()
            }
        )
        // 左侧颜色竖条（与预览页一致）
        Box(
            Modifier
                .width(4.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(androidx.compose.ui.graphics.Color(EventColors.argb(event.color.toLong())))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                event.title,
                fontSize = 16.5.sp,
                fontWeight = FontWeight.Medium,
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
                if (event.reminderMinutes >= 0) {
                    add("提醒 " + com.qingshui.calendar.domain.model.ReminderPresets
                        .labelOf(event.reminderMinutes))
                }
            }.filter { it.isNotBlank() }
            Text(
                listOf(timeText, *extra.toTypedArray()).joinToString(" · "),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (event.location.isNotBlank()) {
                Text(
                    "地点：" + event.location,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (event.description.isNotBlank()) {
                Text(
                    event.description,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
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
