@file:OptIn(ExperimentalFoundationApi::class)

package com.qingshui.calendar.ui.year

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.qingshui.calendar.domain.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

// ---------------------------------------------------------------- ViewModel

class YearViewModel(private val c: AppContainer) : ViewModel() {

    val baseYear: Int = LocalDate.now().year

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _year = MutableStateFlow(baseYear)
    val year: StateFlow<Int> = _year.asStateFlow()

    init {
        viewModelScope.launch {
            c.settingsRepository.settings.collect { _settings.value = it }
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

private const val PAGES = 24000
private const val CENTER = 12000

@Composable
fun YearScreen(
    factory: ViewModelProvider.Factory,
    onJumpToMonth: (LocalDate) -> Unit
) {
    val vm: YearViewModel = viewModel(factory = factory)
    val settings by vm.settings.collectAsState()
    val year by vm.year.collectAsState()

    val pagerState = rememberPagerState(initialPage = CENTER, pageCount = { PAGES })

    LaunchedEffect(pagerState.currentPage) {
        vm.setYear(vm.baseYear + (pagerState.currentPage - CENTER))
    }
    LaunchedEffect(year) {
        val target = CENTER + ChronoUnit.YEARS.between(vm.baseYear, year)
        if (target in 0 until PAGES && pagerState.currentPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${year} 年",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { vm.setYear(year - 1) }) { Text("‹", fontSize = 22.sp) }
            TextButton(onClick = { vm.goToToday() }) { Text("今年") }
            TextButton(onClick = { vm.setYear(year + 1) }) { Text("›", fontSize = 22.sp) }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { page ->
            val y = vm.baseYear + (page - CENTER)
            YearGrid(
                year = y,
                settings = settings,
                today = LocalDate.now(),
                onClickDay = onJumpToMonth
            )
        }
    }
}

@Composable
private fun YearGrid(
    year: Int,
    settings: AppSettings,
    today: LocalDate,
    onClickDay: (LocalDate) -> Unit
) {
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
    onClickDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
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
                .clickable { onClickDay(month.atDay(1)) }
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
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(vertical = 1.dp),
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
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .clickable { onClickDay(d) }
                                .padding(2.dp)
                        )
                    }
                }
            }
        }
    }
}
