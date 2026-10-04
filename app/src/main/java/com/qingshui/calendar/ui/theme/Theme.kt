package com.qingshui.calendar.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** 清水青（主色，与 EventColors.palette 首位一致） */
val QingshuiTeal = Color(0xFF2A9D8F)

/** 主题：themeMode 0 跟随系统 / 1 浅色 / 2 深色；Android 12+ 可用动态取色 */
@Composable
fun QingshuiTheme(
    themeMode: Int = 0,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> darkColorScheme(primary = QingshuiTeal, secondary = Color(0xFF577590))
        else -> lightColorScheme(primary = QingshuiTeal, secondary = Color(0xFF577590))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
