package com.qingshui.calendar.ui.components

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * 触感 + 音效反馈。
 *
 * 手感设计（对着成品 App 来的）：
 *  - **分场景**：轻点 / 选择 / 确认 / 警示 / 长按，各自用不同的系统触感常量与音效，
 *    而不是所有操作都"嗡"一下——轻点要脆、确认要实、删除要"拒绝"感。
 *  - **节流**：40ms 内的重复触发只算一次，连点日期/切 Tab 不会嗡嗡响。
 *  - **尊重系统**：`playSoundEffect` / `performHapticFeedback` 在用户关掉系统
 *    「触摸提示音」或「触感反馈」后会自动静默，不会绕过系统设置。
 *  - **可关闭**：设置页有总开关，关掉后连系统接口都不调用。
 *  - **低版本降级**：Android 11 才有 CONFIRM/REJECT 触感、Android 13 才有方向音，
 *    低版本自动退回 KEYBOARD_TAP / CLICK。
 */
enum class Fx {
    /** 轻点：选日期、切标签、切换开关 */
    TICK,

    /** 选择：翻页、选中、展开收起 */
    SELECT,

    /** 确认：保存、新建、勾选完成 */
    CONFIRM,

    /** 警示：删除、清空、失败 */
    WARN,

    /** 长按：进入编辑、拖动 */
    LONG_PRESS
}

interface AppFeedback {

    fun fire(kind: Fx = Fx.TICK)

    fun tick() = fire(Fx.TICK)
    fun select() = fire(Fx.SELECT)
    fun confirm() = fire(Fx.CONFIRM)
    fun warn() = fire(Fx.WARN)
    fun longPress() = fire(Fx.LONG_PRESS)

    /** 翻月/翻年：左右方向用不同的音（Android 13+） */
    fun page(forward: Boolean)

    /** 兜底实现：没有 View 时（预览、单元测试）静默 */
    object Noop : AppFeedback {
        override fun fire(kind: Fx) = Unit
        override fun page(forward: Boolean) = Unit
    }
}

private class ViewFeedback(
    private val view: View,
    private val soundOn: Boolean,
    private val hapticOn: Boolean
) : AppFeedback {

    private var lastAt = 0L

    override fun fire(kind: Fx) {
        if (kind == Fx.WARN) {
            // 警示音效不与普通点击争抢节流，单独放行
            lastAt = SystemClock.uptimeMillis()
            if (hapticOn) view.performHapticFeedback(hapticConstant(kind))
            if (soundOn) view.playSoundEffect(SoundEffectConstants.CLICK)
            return
        }
        if (!soundOn && !hapticOn) return
        if (!allow()) return
        if (hapticOn) view.performHapticFeedback(hapticConstant(kind))
        if (soundOn) view.playSoundEffect(SoundEffectConstants.CLICK)
    }

    override fun page(forward: Boolean) {
        if (!soundOn && !hapticOn) return
        if (!allow()) return
        if (soundOn) {
            // 方向音常量是 Android 13 才公开的；低版本退回普通点击音
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                view.playSoundEffect(
                    if (forward) SoundEffectConstants.NAVIGATION_RIGHT
                    else SoundEffectConstants.NAVIGATION_LEFT
                )
            } else {
                view.playSoundEffect(SoundEffectConstants.CLICK)
            }
        }
        if (hapticOn) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun allow(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastAt < THROTTLE_MS) return false
        lastAt = now
        return true
    }

    private fun hapticConstant(kind: Fx): Int = when (kind) {
        Fx.TICK -> HapticFeedbackConstants.KEYBOARD_TAP
        Fx.SELECT -> HapticFeedbackConstants.CONTEXT_CLICK
        Fx.CONFIRM ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY
        Fx.WARN ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
            else HapticFeedbackConstants.LONG_PRESS
        Fx.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
    }

    private companion object {
        /** 连点保护：这段时间内的重复反馈合并成一次 */
        const val THROTTLE_MS = 40L
    }
}

/**
 * 全局反馈入口。`AppRoot` 里 provides 一次，所有界面直接
 * `LocalAppFeedback.current.tick()` 即可；没提供时是静默实现，不会崩。
 */
val LocalAppFeedback = staticCompositionLocalOf<AppFeedback> { AppFeedback.Noop }

/** 在 AppRoot 里建实例，随设置开关自动重建 */
@Composable
fun rememberAppFeedback(soundOn: Boolean, hapticOn: Boolean): AppFeedback {
    val view = LocalView.current
    return remember(view, soundOn, hapticOn) { ViewFeedback(view, soundOn, hapticOn) }
}
