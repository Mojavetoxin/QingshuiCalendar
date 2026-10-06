package com.qingshui.calendar.ui.components

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * 触感 + 音效反馈。
 *
 * 手感设计（对着成品 App 来的）：
 *  - **分场景**：轻点 / 选择 / 确认 / 完成 / 警示 / 失败 / 长按 / 展开 / 收起，
 *    各自用不同的系统触感常量与合成音，而不是所有操作都"嗡"一下 ——
 *    轻点要脆、确认要实、完成要爽、删除要有"拒绝"感。
 *  - **节流**：40ms 内的重复触发只算一次，连点日期/切 Tab 不会嗡嗡响。
 *    （警示与失败**不参与节流**，破坏性操作必须每次都反馈到位。）
 *  - **尊重系统**：`playSoundEffect` / `performHapticFeedback` 在用户关掉系统
 *    「触摸提示音」或「触感反馈」后会自动静默，不会绕过系统设置；
 *    系统触摸音被关掉时，声音改走 [SoundKit] 的现场合成兜底（详见 SoundKit 注释）。
 *  - **可关闭**：设置页有音效 / 触感两个**独立**开关，关掉后连系统接口都不调用。
 *  - **低版本降级**：Android 11 才有 CONFIRM/REJECT 触感、Android 13 才有方向音，
 *    低版本自动退回等效常量。
 */
enum class Fx {
    /** 轻点：选日期、切标签、切换开关 */
    TICK,

    /** 选择：翻页、选中、展开收起 */
    SELECT,

    /** 确认：保存、新建 */
    CONFIRM,

    /** 完成：勾选日程完成 —— 三音上行，要有"搞定"的爽感 */
    SUCCESS,

    /** 警示：删除、清空 —— 低音下行 */
    WARN,

    /** 失败：解析失败、导入出错 —— 更低更闷的下行 */
    ERROR,

    /** 长按：进入编辑、拖动 */
    LONG_PRESS,

    /** 展开：下拉放大出周视图 —— 上行滑音 */
    EXPAND,

    /** 收起：收回整月 —— 下行滑音 */
    COLLAPSE,

    /** 向后翻页：向右上滑音 */
    SLIDE_FWD,

    /** 向前翻页：向左下滑音 */
    SLIDE_BACK
}

interface AppFeedback {

    fun fire(kind: Fx = Fx.TICK)

    fun tick() = fire(Fx.TICK)
    fun select() = fire(Fx.SELECT)
    fun confirm() = fire(Fx.CONFIRM)
    fun success() = fire(Fx.SUCCESS)
    fun warn() = fire(Fx.WARN)
    fun error() = fire(Fx.ERROR)
    fun longPress() = fire(Fx.LONG_PRESS)
    fun expand() = fire(Fx.EXPAND)
    fun collapse() = fire(Fx.COLLAPSE)

    /** 翻月/翻年/翻日：左右方向用不同的音 */
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
    private val hapticOn: Boolean,
    private val volume: Int
) : AppFeedback {

    private var lastAt = 0L

    override fun fire(kind: Fx) {
        // 警示与失败不与普通点击争抢节流：破坏性/出错必须每次都反馈到位
        if (kind == Fx.WARN || kind == Fx.ERROR) {
            lastAt = SystemClock.uptimeMillis()
            if (hapticOn) view.performHapticFeedback(hapticConstant(kind))
            if (soundOn) SoundKit.play(view, kind, volume)
            return
        }
        if (!soundOn && !hapticOn) return
        if (!allow()) return
        if (hapticOn) view.performHapticFeedback(hapticConstant(kind))
        if (soundOn) SoundKit.play(view, kind, volume)
    }

    override fun page(forward: Boolean) {
        if (!soundOn && !hapticOn) return
        if (!allow()) return
        if (soundOn) SoundKit.playPage(view, forward, volume)
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
        Fx.SELECT, Fx.EXPAND, Fx.COLLAPSE, Fx.SLIDE_FWD, Fx.SLIDE_BACK ->
            HapticFeedbackConstants.CONTEXT_CLICK
        Fx.CONFIRM, Fx.SUCCESS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY
        Fx.WARN, Fx.ERROR ->
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

/** 在 AppRoot 里建实例，随设置（开关 / 音量）自动重建 */
@Composable
fun rememberAppFeedback(soundOn: Boolean, hapticOn: Boolean, volume: Int = 55): AppFeedback {
    val view = LocalView.current
    return remember(view, soundOn, hapticOn, volume) {
        ViewFeedback(view, soundOn, hapticOn, volume)
    }
}
