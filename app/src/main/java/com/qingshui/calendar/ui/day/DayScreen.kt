package com.qingshui.calendar.ui.day

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qingshui.calendar.di.AppContainer
import com.qingshui.calendar.domain.calendar.CalendarUtils
import com.qingshui.calendar.domain.calendar.HolidayCalendar
import com.qingshui.calendar.domain.calendar.LunarCalendar
import com.qingshui.calendar.domain.model.AppSettings
import com.qingshui.calendar.domain.model.EventOccurrence
import com.qingshui.calendar.ui.components.EmptyHint
import com.qingshui.calendar.ui.components.LocalAppFeedback
import com.qingshui.calendar.ui.month.EventRow
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 单日视图的数据窗口（左右各 ±180 天，横滑够用） */
private const val WINDOW_DAYS = 180L

/**
 * 日视图 ViewModel：以"今天"为基准的大页数 Pager，横滑即切换昨天/明天。
 * 展开一段日期窗口的日程，横滑时不至于出现空白。
 */
class DayViewModel(private val c: AppContainer) : ViewModel() {

    val baseDate: LocalDate = LocalDate.now()

    val settings: StateFlow<AppSettings> =
        c.settingsRepository.settings
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _selected = MutableStateFlow(LocalDate.now())
    val selected: StateFlow<LocalDate> = _selected.asStateFlow()

    private val _occurrences = MutableStateFlow<Map<LocalDate, List<EventOccurrence>>>(emptyMap())
    val occurrences: StateFlow<Map<LocalDate, List<EventOccurrence>>> = _occurrences.asStateFlow()

    init {
        viewModelScope.launch {
            c.eventRepository
                .observeBetween(baseDate.minusDays(WINDOW_DAYS), baseDate.plusDays(WINDOW_DAYS))
                .collect { list -> _occurrences.value = list.groupBy { it.date } }
        }
    }

    fun select(d: LocalDate) {
        _selected.value = d
    }

    fun goToToday() {
        _selected.value = LocalDate.now()
    }

    fun toggleDone(o: EventOccurrence) {
        viewModelScope.launch {
            val e = o.event
            c.eventRepository.upsert(e.copy(status = if (e.status == 1) 0 else 1))
            c.alarmScheduler.rescheduleNext()
        }
    }
}

/**
 * 日视图页面要用的动态输入（在 DayScreen 的普通组合里构造，天然随数据更新）。 */
data class DayInput(
    val settings: AppSettings,
    val occurrences: Map<LocalDate, List<EventOccurrence>>
)

@Composable
fun DayScreen(
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
    onEditEvent: (Long) -> Unit
) {
    val vm: DayViewModel = viewModel(factory = factory)
    val selected by vm.selected.collectAsState()
    val settings by vm.settings.collectAsState()
    val occurrences by vm.occurrences.collectAsState()
    val fx = LocalAppFeedback.current
    val today = remember { LocalDate.now() }

    var dragX by remember { mutableStateOf(0f) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { fx.tick(); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "日历",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { fx.select(); vm.goToToday() }) { Text("今天") }
        }

        // 直接渲染选中日期（同样不用 Pager：页面在子组合里，日程列表会一直是空的）
        // 左右滑动切换昨天 / 明天
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(selected) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (abs(dragX) > 60f) {
                                val forward = dragX < 0f
                                fx.page(forward)
                                vm.select(selected.plusDays(if (forward) 1L else -1L))
                            }
                            dragX = 0f
                        },
                        onDragCancel = { dragX = 0f }
                    ) { _, dragAmount ->
                        dragX += dragAmount
                    }
                }
        ) {
            DayPage(
                date = selected,
                input = DayInput(settings, occurrences),
                today = today,
                onEditEvent = onEditEvent,
                onToggleDone = { vm.toggleDone(it) }
            )
        }
    }
}


@Composable
private fun DayPage(
    date: LocalDate,
    input: DayInput,
    today: LocalDate,
    onEditEvent: (Long) -> Unit,
    onToggleDone: (EventOccurrence) -> Unit
) {
    val settings = input.settings
    val lunar = LunarCalendar.from(date)
    val ganZhiYear = LunarCalendar.ganZhiYear(date.year)
    val ganZhiMonth = LunarCalendar.ganZhiMonth(date.year, lunar?.month ?: 1)
    val ganZhiDay = LunarCalendar.ganZhiDay(date)
    val holiday = if (settings.showHoliday) HolidayCalendar.info(date) else null
    val isMakeup = settings.showHoliday && HolidayCalendar.isMakeupWorkday(date)
    val events = input.occurrences[date].orEmpty()

    val diff = ChronoUnit.DAYS.between(today, date)
    val dayChip = when (diff) {
        0L -> "今天"
        1L -> "明天"
        -1L -> "昨天"
        else -> if (diff > 0) "$diff 天后" else "${-diff} 天前"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 24.dp)
    ) {
        Spacer(Modifier.height(10.dp))

        // ── 大日期 ──
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${date.dayOfMonth}",
                fontSize = 60.sp,
                lineHeight = 62.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.padding(bottom = 6.dp)) {
                Text(
                    "${date.monthValue} 月",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    weekdayText(date),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                dayChip,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }

        Spacer(Modifier.height(14.dp))

        // ── 干支纪年月日 ──
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "$ganZhiYear年 · $ganZhiMonth月 · $ganZhiDay日",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "农历" + (LunarCalendar.lunarMonthDay(date).ifEmpty { "—" }) +
                    " · 生肖" + LunarCalendar.animalYear(date.year),
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── 详细信息 ──
        val term = nearestSolarTerm(date)
        if (holiday != null) {
            InfoRow(
                "假期",
                holiday.name + if (holiday.isOffDay) " · 休" else " · 调休上班"
            )
        } else if (isMakeup) {
            InfoRow("假期", "调休上班")
        }
        if (term != null) {
            val (name, termDate) = term
            InfoRow(
                "节气",
                if (termDate == date) "今天 · $name"
                else "$name · ${termDate.monthValue}月${termDate.dayOfMonth}日" +
                    "（${ChronoUnit.DAYS.between(date, termDate)} 天后）"
            )
        }
        if (lunar != null) {
            InfoRow(
                "农历",
                (if (lunar.isLeap) "闰" else "") + LunarCalendar.monthName(lunar) +
                    LunarCalendar.dayName(lunar.day)
            )
        }
        InfoRow("周次", "第 ${CalendarUtils.weekNumber(date, settings.weekStartMonday)} 周")
        InfoRow(
            "年内",
            "第 ${date.dayOfYear} 天 · 还剩 ${date.lengthOfYear() - date.dayOfYear} 天"
        )

        Spacer(Modifier.height(18.dp))

        // ── 当天日程 ──
        Text(
            "当天日程" + if (events.isEmpty()) "" else "（${events.size}）",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        if (events.isEmpty()) {
            EmptyHint("这一天没有日程")
        } else {
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
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        Text(value, fontSize = 14.5.sp)
    }
}

private fun weekdayText(date: LocalDate): String =
    listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[date.dayOfWeek.value - 1]

/** 从 date 起往后找最近的节气（当天有则返回当天） */
private fun nearestSolarTerm(date: LocalDate): Pair<String, LocalDate>? {
    for (i in 0..20) {
        val d = date.plusDays(i.toLong())
        val n = LunarCalendar.solarTermName(d)
        if (n != null) return n to d
    }
    return null
}
