package com.qingshui.calendar.ui.nav

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.qingshui.calendar.R
import com.qingshui.calendar.di.AppContainer
import com.qingshui.calendar.domain.model.AppSettings
import com.qingshui.calendar.ui.edit.EventEditScreen
import com.qingshui.calendar.ui.edit.EventEditViewModel
import com.qingshui.calendar.ui.importscreen.ImportScreen
import com.qingshui.calendar.ui.importscreen.ImportViewModel
import com.qingshui.calendar.ui.list.EventListScreen
import com.qingshui.calendar.ui.list.EventListViewModel
import com.qingshui.calendar.ui.month.MonthScreen
import com.qingshui.calendar.ui.month.MonthViewModel
import com.qingshui.calendar.ui.components.LocalAppFeedback
import com.qingshui.calendar.ui.components.rememberAppFeedback
import com.qingshui.calendar.ui.day.DayScreen
import com.qingshui.calendar.ui.day.DayViewModel
import com.qingshui.calendar.ui.settings.SettingsScreen
import com.qingshui.calendar.ui.settings.SettingsViewModel
import com.qingshui.calendar.ui.year.YearScreen
import com.qingshui.calendar.ui.year.YearViewModel
import kotlinx.coroutines.flow.first

/** 五个标签页的共享 factory（编辑页 VM 带参数，在路由里单独建） */
class VmFactory(private val c: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(MonthViewModel::class.java) -> MonthViewModel(c) as T
        modelClass.isAssignableFrom(YearViewModel::class.java) -> YearViewModel(c) as T
        modelClass.isAssignableFrom(EventListViewModel::class.java) -> EventListViewModel(c) as T
        modelClass.isAssignableFrom(ImportViewModel::class.java) -> ImportViewModel(c) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(c) as T
        modelClass.isAssignableFrom(DayViewModel::class.java) -> DayViewModel(c) as T
        else -> throw IllegalArgumentException("Unknown ViewModel: $modelClass")
    }
}

private data class TabItem(
    val route: String,
    val label: String,
    val vector: ImageVector? = null,
    @DrawableRes val resId: Int? = null
)

private val TABS = listOf(
    TabItem("tab_month", "月", vector = Icons.Filled.DateRange),
    TabItem("tab_year", "年", resId = R.drawable.ic_dashboard),
    TabItem("tab_list", "日程", vector = Icons.Filled.List),
    TabItem("tab_import", "导入", resId = R.drawable.ic_file_download),
    TabItem("tab_settings", "设置", vector = Icons.Filled.Settings)
)

@Composable
fun AppRoot(c: AppContainer) {
    // 反馈总开关随设置变化，关掉后完全不调用系统音效/触感接口
    val settings by c.settingsRepository.settings.collectAsState(initial = AppSettings())
    val feedback = rememberAppFeedback(settings.soundEnabled, settings.hapticEnabled)
    CompositionLocalProvider(LocalAppFeedback provides feedback) {
        AppScaffold(c)
    }
}

@Composable
private fun AppScaffold(c: AppContainer) {
    val navController = rememberNavController()
    val factory = remember { VmFactory(c) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ""

    // 通知点击 → 打开对应日程编辑页
    LaunchedEffect(Unit) {
        c.pendingEventId.collect { id ->
            if (id != null) {
                c.pendingEventId.value = null
                navController.navigate("event_edit/$id")
            }
        }
    }

    // 首次启动申请通知权限（Android 13+）
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也不影响使用，只是收不到提醒通知 */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            val asked = c.settingsRepository.settings.first().notifPermissionAsked
            if (!asked) {
                c.settingsRepository.setNotifPermissionAsked()
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val feedback = LocalAppFeedback.current

    fun goTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        floatingActionButton = {
            if (currentRoute == "tab_month" || currentRoute == "tab_list") {
                FloatingActionButton(onClick = {
                    feedback.confirm(); navController.navigate("event_edit/-1")
                }) {
                    Icon(Icons.Filled.Add, contentDescription = "新建日程")
                }
            }
        },
        bottomBar = {
            if (currentRoute.startsWith("tab_")) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { feedback.select(); goTab(tab.route) },
                            icon = {
                                when {
                                    tab.vector != null -> Icon(tab.vector, contentDescription = tab.label)
                                    tab.resId != null -> Icon(
                                        painterResource(tab.resId),
                                        contentDescription = tab.label
                                    )
                                }
                            },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "tab_month",
            modifier = Modifier.padding(padding)
        ) {
            composable("tab_month") {
                MonthScreen(
                    factory = factory,
                    onEditEvent = { navController.navigate("event_edit/$it") },
                    onOpenDay = { navController.navigate("day") }
                )
            }
            composable("tab_year") {
                YearScreen(
                    factory = factory,
                    onJumpToMonth = { date ->
                        c.monthFocusChannel.trySend(date)
                        goTab("tab_month")
                    }
                )
            }
            composable("tab_list") {
                EventListScreen(
                    factory = factory,
                    onEditEvent = { navController.navigate("event_edit/$it") }
                )
            }
            composable("tab_import") {
                ImportScreen(
                    factory = factory,
                    onEditEvent = { navController.navigate("event_edit/$it") }
                )
            }
            composable("tab_settings") {
                SettingsScreen(factory = factory)
            }
            composable("day") {
                DayScreen(
                    factory = factory,
                    onBack = { navController.popBackStack() },
                    onEditEvent = { navController.navigate("event_edit/$it") }
                )
            }
            composable("event_edit/{eventId}") { entry ->
                val id = entry.arguments?.getString("eventId")?.toLongOrNull() ?: -1L
                val vm: EventEditViewModel = viewModel(
                    key = "event_edit_$id",
                    factory = viewModelFactory { initializer { EventEditViewModel(c, id) } }
                )
                EventEditScreen(vm) { navController.popBackStack() }
            }
        }
    }
}
