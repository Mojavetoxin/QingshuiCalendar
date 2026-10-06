package com.qingshui.calendar.ui.day

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 单日视图的数据窗口（左右各 ±180 天，横滑够用） */
private const val WINDOW_DAYS = 180L

/**
 * 相邻卡片在屏幕边缘露出的宽度（"侧边虚影"）。
 * ★ 这个值直接决定两侧邻居「看得见多少」：邻居可见宽 ≈ PEEK − GAP − (卡宽 × (1−scale)/2)。
 *   实测 34dp + scale 0.94 时只剩 ~12dp，几乎就是一条线；提到 42dp 后约 20dp，明显是"一张卡"。
 */
private val PEEK = 42.dp

/** 卡片之间的水平间距（配合 PEEK 调：gap 越大，邻居露出的净宽越小） */
private val CARD_GAP = 10.dp

/** 非中心卡片的缩放（越小越"退后"）。0.92 = 一眼看出比中间小，但仍认得出是卡片 */
private const val SIDE_SCALE = 0.92f

/**
 * 非中心卡片的透明度。0.42 太淡、几乎看不见；0.62 是"虚影"与"看得见"的平衡点。
 * 注意：缩放会让邻居边缘向内收，所以 scale 与 PEEK 要一起调大，否则越缩越看不见。
 */
private const val SIDE_ALPHA = 0.62f

/**
 * 日视图 ViewModel：以"今天"为基准展开一段日期窗口的日程，横滑切换昨天/明天。
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
 * 日视图页面要用的动态输入（在 DayScreen 的普通组合里构造，天然随数据更新）。
 */
data class DayInput(
    val settings: AppSettings,
    val occurrences: Map<LocalDate, List<EventOccurrence>>
)

/**
 * 日历（单日）视图。
 *
 * 布局是**卡片轮播**：屏幕中间是当天的完整卡片，左右两侧各露出一小条相邻日期的"虚影"
 * （半透明 + 略缩小），一眼就能看出可以左右滑。
 *
 * 刻意**不用 `HorizontalPager`** —— Pager 的每一页跑在子组合里，页面不会随外部数据重组
 * （实测圆点/列表永远是空的）。这里改成"一个 Row 放三张卡片 + 用 offset 平移"，
 * 拖拽实时跟手，松手按阈值决定切页还是弹回。
 */
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

    val input = DayInput(settings, occurrences)

    Column(Modifier.fillMaxSize()) {

        // ── 顶栏 ──
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

        // ── 卡片轮播 ──
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val density = LocalDensity.current
            val cardW: Dp = maxWidth - PEEK * 2
            val cardH: Dp = maxHeight          // weight(1f) 槽位给了有界高度，卡片直接用它
            val cardWPx = with(density) { cardW.toPx() }
            val gapPx = with(density) { CARD_GAP.toPx() }
            val peekPx = with(density) { PEEK.toPx() }
            // Row 的起点：让中间那张卡片正好居中（两侧各露 PEEK - GAP）
            val homeX = peekPx - cardWPx - gapPx

            val scope = rememberCoroutineScope()
            val offsetX = remember { Animatable(homeX) }
            // 只在「当前没有正在跑的动画」时归位，避免把用户拖到一半的卡片弹回去
            LaunchedEffect(homeX) {
                if (offsetX.value != homeX && !offsetX.isRunning) offsetX.snapTo(homeX)
            }

            // ★ wrapContentSize(unbounded = true) 是关键，别删：
            // Row 的默认行为会用父级的 maxWidth(≈屏宽) 把 3 张卡「夹断」，
            // 第 2、3 张卡被排到父级宽度之外，Row 自身只报一个屏宽；
            // 再叠加下面 homeX ≈ -1 张卡宽的 offset，整行就被推出屏幕外 —— 界面看起来全空（实测过）。
            // 解开宽度约束后，Row 才能真的线性排开 3 张卡，offset 平移才有意义。
            val curSelected by rememberUpdatedState(selected)
            Row(
                Modifier
                    .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                    // 只以 cardWPx 为 key：如果把 selected 也当 key，切换日期会让手势块
                    // 在切换动画中途被取消重建，offsetX 可能停在「偏一整张卡」的位置回不来。
                    .pointerInput(cardWPx) {
                        var acc = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { acc = 0f },
                            onDragEnd = {
                                val moved = offsetX.value - homeX
                                if (abs(acc) > 24f && abs(moved) > cardWPx * 0.22f) {
                                    val forward = moved < 0f          // 向左拖 = 看下一天
                                    val target = homeX + (if (forward) -1f else 1f) * (cardWPx + gapPx)
                                    scope.launch {
                                        offsetX.animateTo(
                                            target,
                                            tween(190, easing = FastOutSlowInEasing)
                                        )
                                        vm.select(curSelected.plusDays(if (forward) 1L else -1L))
                                        offsetX.snapTo(homeX)
                                    }
                                    fx.page(forward)
                                } else {
                                    scope.launch {
                                        offsetX.animateTo(
                                            homeX,
                                            spring(dampingRatio = 0.78f, stiffness = 420f)
                                        )
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch { offsetX.animateTo(homeX, tween(160)) }
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            acc += dragAmount
                            // 拖动时**实时跟手**（用 launch + snapTo，官方推荐写法）
                            scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                        }
                    }
            ) {
                DayCard(
                    date = selected.minusDays(1),
                    input = input,
                    today = today,
                    isCenter = false,
                    width = cardW,
                    height = cardH,
                    onEditEvent = onEditEvent,
                    onToggleDone = { vm.toggleDone(it) }
                )
                Spacer(Modifier.width(CARD_GAP))
                DayCard(
                    date = selected,
                    input = input,
                    today = today,
                    isCenter = true,
                    width = cardW,
                    height = cardH,
                    onEditEvent = onEditEvent,
                    onToggleDone = { vm.toggleDone(it) }
                )
                Spacer(Modifier.width(CARD_GAP))
                DayCard(
                    date = selected.plusDays(1),
                    input = input,
                    today = today,
                    isCenter = false,
                    width = cardW,
                    height = cardH,
                    onEditEvent = onEditEvent,
                    onToggleDone = { vm.toggleDone(it) }
                )
            }
        }

        // ── 滑动提示（两侧虚影已经很直观，这行只做补充） ──
        Text(
            "← 左右滑动切换日期 →",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 一张日期卡片。isCenter=false 时降透明度 + 轻微缩小，成为"侧边虚影"。
 */
@Composable
private fun DayCard(
    date: LocalDate,
    input: DayInput,
    today: LocalDate,
    isCenter: Boolean,
    width: Dp,
    height: Dp,
    onEditEvent: (Long) -> Unit,
    onToggleDone: (EventOccurrence) -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .width(width)
            // 用父级算好的明确高度，不用 fillMaxHeight()（Row 的高度由子项决定，
            // 子项再 fillMaxHeight() 会绕成死循环；放进 verticalScroll 还会直接抛异常）
            .height(height)
            .graphicsLayer {
                alpha = if (isCenter) 1f else SIDE_ALPHA
                val s = if (isCenter) 1f else SIDE_SCALE
                scaleX = s
                scaleY = s
            }
            .then(
                // 中心卡：明显投影（"浮"起来）；侧卡：浅浅一层投影，让它读起来仍是"卡片"而非色块
                if (isCenter) Modifier.shadow(10.dp, shape, clip = false)
                else Modifier.shadow(4.dp, shape, clip = false)
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                shape
            )
    ) {
        DayContent(
            date = date,
            input = input,
            today = today,
            onEditEvent = onEditEvent,
            onToggleDone = onToggleDone
        )
    }
}

@Composable
private fun DayContent(
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
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 20.dp)
    ) {
        // ── 大日期 ──
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${date.dayOfMonth}",
                fontSize = 56.sp,
                lineHeight = 58.sp,
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
                    .padding(horizontal = 11.dp, vertical = 5.dp)
            )
        }

        Spacer(Modifier.height(14.dp))

        // ── 干支纪年月日 ──
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(vertical = 13.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 注意：Kotlin 标识符允许汉字，写成 "$ganZhiYear年" 会把「年」吞进变量名 → 必须用 ${}
            Text(
                "${ganZhiYear}年 · ${ganZhiMonth}月 · ${ganZhiDay}日",
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

        Spacer(Modifier.height(14.dp))

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

        Spacer(Modifier.height(16.dp))

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
