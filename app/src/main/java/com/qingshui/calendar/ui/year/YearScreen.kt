@file:OptIn(ExperimentalFoundationApi::class)

package com.qingshui.calendar.ui.year

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qingshui.calendar.di.AppContainer
import com.qingshui.calendar.domain.calendar.CalendarUtils
import com.qingshui.calendar.domain.calendar.LunarCalendar
import com.qingshui.calendar.domain.model.AppSettings
import com.qingshui.calendar.domain.model.EventOccurrence
import com.qingshui.calendar.ui.components.LocalAppFeedback
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- ViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class YearViewModel(private val c: AppContainer) : ViewModel() {

    val baseYear: Int = LocalDate.now().year

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _year = MutableStateFlow(baseYear)
    val year: StateFlow<Int> = _year.asStateFlow()

    /** 整年的日程出现（按日期分组），用于迷你月标注「有日程」的小红点 */
    private val _occurrences = MutableStateFlow<Map<LocalDate, List<EventOccurrence>>>(emptyMap())
    val occurrences: StateFlow<Map<LocalDate, List<EventOccurrence>>> = _occurrences.asStateFlow()

    init {
        viewModelScope.launch {
            c.settingsRepository.settings.collect { _settings.value = it }
        }
        viewModelScope.launch {
            _year.flatMapLatest { y ->
                c.eventRepository.observeBetween(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31))
            }.collect { list ->
                _occurrences.value = list.groupBy { it.date }
            }
        }
    }

    fun setYear(y: Int) {
        if (y in 1901..2399) _year.value = y
    }

    fun goToToday() {
        _year.value = baseYear
    }
}

// ---------------------------------------------------------------- 屏幕

@Composable
fun YearScreen(
    factory: ViewModelProvider.Factory,
    onJumpToMonth: (LocalDate) -> Unit
) {
    val vm: YearViewModel = viewModel(factory = factory)
    val year by vm.year.collectAsState()
    val settings by vm.settings.collectAsState()
    val occurrences by vm.occurrences.collectAsState()
    val fx = LocalAppFeedback.current

    var dragX by remember { mutableStateOf(0f) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${year} 年 · ${LunarCalendar.ganZhiYear(year)}年",
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { fx.page(false); vm.setYear(year - 1) }) {
                Text("‹", fontSize = 22.sp)
            }
            TextButton(onClick = { fx.select(); vm.goToToday() }) { Text("今年") }
            TextButton(onClick = { fx.page(true); vm.setYear(year + 1) }) {
                Text("›", fontSize = 22.sp)
            }
        }

        // 直接渲染当前年份（不用 Pager：页面在子组合里不会随数据重组，迷你月的日程红点不会出现）
        // 左右滑动切换年份
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(year) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (abs(dragX) > 60f) {
                                val forward = dragX < 0f
                                fx.page(forward)
                                vm.setYear(if (forward) year + 1 else year - 1)
                            }
                            dragX = 0f
                        },
                        onDragCancel = { dragX = 0f }
                    ) { _, dragAmount ->
                        dragX += dragAmount
                    }
                }
        ) {
            YearGrid(
                year = year,
                input = YearGridInput(settings, occurrences),
                today = LocalDate.now(),
                onClickDay = onJumpToMonth
            )
        }
    }
}


@Composable
private fun YearGrid(
    year: Int,
    input: YearGridInput,
    today: LocalDate,
    onClickDay: (LocalDate) -> Unit
) {
    val settings = input.settings
    val occurrences = input.occurrences
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        for (row in 0..3) {
            Row(Modifier.fillMaxWidth().height(196.dp)) {
                for (col in 0..2) {
                    val m = row * 3 + col + 1
                    MiniMonth(
                        month = YearMonth.of(year, m),
                        settings = settings,
                        today = today,
                        occurrences = occurrences,
                        onClickDay = onClickDay,
                        modifier = Modifier
                            .weight(1f)
                            .padding(4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniMonth(
    month: YearMonth,
    settings: AppSettings,
    today: LocalDate,
    occurrences: Map<LocalDate, List<EventOccurrence>>,
    onClickDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val fx = LocalAppFeedback.current
    val wsMonday = settings.weekStartMonday
    val dates = remember(month, wsMonday) { CalendarUtils.gridDates(month, wsMonday) }
    val labels = remember(wsMonday) { CalendarUtils.weekdayLabels(wsMonday) }

    Column(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(4.dp)
    ) {
        Text(
            "${month.monthValue}月",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { fx.tick(); onClickDay(month.atDay(1)) }
                .padding(vertical = 2.dp)
        )
        Row(Modifier.fillMaxWidth()) {
            labels.forEach {
                Text(
                    it,
                    fontSize = 8.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        dates.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { d ->
                    val inMonth = d.monthValue == month.monthValue
                    val isToday = d == today
                    val weekend = d.dayOfWeek.value == 6 || d.dayOfWeek.value == 7
                    val color = when {
                        isToday -> MaterialTheme.colorScheme.onPrimary
                        !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                        weekend -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(vertical = 1.dp)
                            .clickable { fx.tick(); onClickDay(d) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier.width(16.dp).height(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isToday) {
                                Box(
                                    Modifier
                                        .width(16.dp)
                                        .height(16.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                            Text(
                                "${d.dayOfMonth}",
                                fontSize = 9.sp,
                                lineHeight = 12.sp,
                                color = color,
                                textAlign = TextAlign.Center
                            )
                        }
                        // 有日程的日子：下方标一个红点
                        Box(
                            Modifier
                                .size(3.dp)
                                .clip(CircleShape)
                                .background(
                                    if (occurrences[d].isNullOrEmpty()) Color.Transparent
                                    else MaterialTheme.colorScheme.error
                                )
                        )
                    }
                }
            }
        }
    }
}

/**
 * 年视图网格的动态输入（在 YearScreen 的普通组合里构造，天然随数据更新）。 */
data class YearGridInput(
    val settings: AppSettings,
    val occurrences: Map<LocalDate, List<EventOccurrence>>
)
