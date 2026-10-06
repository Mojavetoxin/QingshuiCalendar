package com.qingshui.calendar.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.view.SoundEffectConstants
import android.view.View
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 音效播放（**零音频资源文件**）。
 *
 * 为什么不能只用 `View.playSoundEffect`：
 * 它走的是系统"触摸提示音"（Settings.System.SOUND_EFFECTS_ENABLED）。**很多国产 ROM（实测小米）默认把它关掉**，
 * 这时 `playSoundEffect` 会静默返回 false —— 表现就是"震动有、声音没有，把震动关了更是彻底没动静"。
 *
 * 所以这里做两手：
 *  1. 系统触摸音开着 → 用 `playSoundEffect`，音色最自然、也尊重系统设置；
 *  2. 关着（或读不到）→ 用 [render] **现场合成**，不需要任何音频资源文件，也不依赖系统开关。
 *
 * 音效设计原则（对着成品 App 来的，2026-10-06 第二轮改为"柔暖"音色）：
 *  - **按语义配谱**，不是所有操作同一声"嘀"：正向操作音高**上行**（听起来"成了"），
 *    负向/警示**下行**（听起来"不行"），展开/收起、左右翻页用**滑音**表达方向。
 *  - **频率下沉 + 木质谐波**：基频落在 520–1560Hz（旧版 1500–2600Hz 像电子嘀声），
 *    谐波以三次为主（[Note.warm]），听感偏木琴/风铃而非蜂鸣器。
 *  - **包络柔和**：衰减常数 2.2（旧 4.2）、起音 4ms 柔坡、收尾 8ms 柔坡，无"啪/咔"杂音。
 *  - **PCM 缓存**：同一音效只合成一次（[cache]），点下去更快出声。
 */
internal object SoundKit {

    private const val SR = 44_100

    private val worker: Handler by lazy {
        val t = HandlerThread("qingshui-sfx").apply { start() }
        Handler(t.looper)
    }

    /** 已合成 PCM 缓存：同一音效只算一次（**按满音量归一化**存放，播放在 [playPcm] 里再乘音量） */
    private val cache = ConcurrentHashMap<Fx, ShortArray>()

    /**
     * @param volume 音量百分比 0..100（界面上的「音效音量」）。0 = 静音。
     *
     * 系统触摸音开着时走 `playSoundEffect` —— 那条路**没法单独调音量**（受系统音量控制），
     * 所以只有音量被调低（<100）时才强制走内置合成音，这样滑块才真正起作用。
     */
    fun play(view: View, kind: Fx, volume: Int) {
        val v = volume.coerceIn(0, 100)
        if (v <= 0) return
        if (v >= 100 && systemTouchSoundOn(view.context)) {
            view.playSoundEffect(SoundEffectConstants.CLICK)
        } else {
            synth(kind, v)
        }
    }

    /** 翻页：左右方向用不同的音（Android 13+ 有公开的方向音常量；合成音用方向滑音） */
    fun playPage(view: View, forward: Boolean, volume: Int) {
        val v = volume.coerceIn(0, 100)
        if (v <= 0) return
        if (v >= 100 && systemTouchSoundOn(view.context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                view.playSoundEffect(
                    if (forward) SoundEffectConstants.NAVIGATION_RIGHT
                    else SoundEffectConstants.NAVIGATION_LEFT
                )
            } else {
                view.playSoundEffect(SoundEffectConstants.CLICK)
            }
        } else {
            synth(if (forward) Fx.SLIDE_FWD else Fx.SLIDE_BACK, v)
        }
    }

    /**
     * 读不到就当作"不可用"，走合成音 —— 宁可音色朴素一点，也不能没声音。
     */
    private fun systemTouchSoundOn(context: Context): Boolean = runCatching {
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.SOUND_EFFECTS_ENABLED, 0
        ) == 1
    }.getOrDefault(false)

    // ─────────────────────────── 音效设计 ───────────────────────────

    /**
     * 一个音符。
     *
     * @param freq    起始频率（Hz）
     * @param ms      时长（毫秒）
     * @param vol     峰值音量（0..1）
     * @param sweepTo 滑音终点频率（0 = 不滑）
     * @param bright  高次谐波（2 次 + 3 次）总量（0..1）：越大越"亮/脆"，越小越"柔/暖"
     * @param warm    三次谐波在 [bright] 里的占比（0..1）。三次谐波偏"木质/钟"感，二次偏"电子"
     */
    private data class Note(
        val freq: Double,
        val ms: Int,
        val vol: Double,
        val sweepTo: Double = 0.0,
        val bright: Double = 0.10,
        val warm: Double = 0.5
    )

    /**
     * 音效谱（**第二轮重新设计**：更柔、更暖、更耐听）。
     *
     * 上一版"听着奇怪"的三个原因，逐条对冲：
     *  1. **频率太高**（轻点 2380Hz、完成音冲到 2637Hz）：像电子"嘀"声，刺耳。
     *     → 全部下沉到 520–1560Hz 的"木琴/风铃"区间，高频只作为轻微泛音出现。
     *  2. **二次谐波偏多**：`sin(2φ)` 加多了是"蜂鸣器"味。
     *     → 改用**三次谐波为主**（`warm=0.7`）：三次谐波听感偏"木质/钟"，比二次柔和。
     *  3. **包络太陡**（`exp(-4.2t)` 36ms 就衰完）：起音"啪"、收尾"断"。
     *     → 衰减常数放到 2.2，起音加 4ms 柔坡，尾巴自然收束不突兀。
     *
     * 语义仍保留：正向上行、负向下行、方向性动作用滑音，但滑音幅度收窄（不再"激光感"）。
     */
    private fun score(kind: Fx): List<Note> = when (kind) {
        // 轻点：一声短而柔的木质点击（选日期、切标签、开关）
        Fx.TICK -> listOf(Note(1046.5, 46, 0.24, bright = 0.10, warm = 0.75))

        // 选择：比轻点低一点、稍长（翻页、选中）
        Fx.SELECT -> listOf(Note(880.0, 60, 0.28, bright = 0.11, warm = 0.7))

        // 确认：两音温和上行（保存、新建）
        Fx.CONFIRM -> listOf(
            Note(659.3, 52, 0.28, bright = 0.12, warm = 0.7),
            Note(987.8, 74, 0.26, bright = 0.14, warm = 0.65)
        )

        // 完成：三音温暖上行（C-G-C 大三和弦分解）—— "搞定"但不用刺耳的高音
        Fx.SUCCESS -> listOf(
            Note(523.3, 58, 0.26, bright = 0.12, warm = 0.7),
            Note(784.0, 58, 0.26, bright = 0.13, warm = 0.68),
            Note(1046.5, 96, 0.26, bright = 0.15, warm = 0.65)
        )

        // 长按：偏低、稍闷
        Fx.LONG_PRESS -> listOf(Note(587.3, 70, 0.28, bright = 0.08, warm = 0.8))

        // 警示：低音下行两下（删除、清空）——柔和但能听出"注意"
        Fx.WARN -> listOf(
            Note(493.9, 92, 0.30, bright = 0.07, warm = 0.8),
            Note(392.0, 122, 0.28, bright = 0.06, warm = 0.85)
        )

        // 失败：更低更闷的下行两音（解析失败、导入出错）
        Fx.ERROR -> listOf(
            Note(349.2, 110, 0.30, bright = 0.06, warm = 0.85),
            Note(261.6, 168, 0.28, bright = 0.05, warm = 0.9)
        )

        // 展开：缓上行滑音（幅度小，不生硬）
        Fx.EXPAND -> listOf(Note(587.3, 96, 0.26, sweepTo = 880.0, bright = 0.12, warm = 0.7))

        // 收起：缓下行滑音
        Fx.COLLAPSE -> listOf(Note(880.0, 96, 0.26, sweepTo = 587.3, bright = 0.12, warm = 0.7))

        // 向后翻页（下一月/下一天）：轻快上行滑音
        Fx.SLIDE_FWD -> listOf(Note(659.3, 90, 0.24, sweepTo = 987.8, bright = 0.11, warm = 0.7))

        // 向前翻页：轻快下行滑音
        Fx.SLIDE_BACK -> listOf(Note(987.8, 90, 0.24, sweepTo = 659.3, bright = 0.11, warm = 0.7))
    }

    private fun synth(kind: Fx, volume: Int) {
        val pcm = cache[kind] ?: render(score(kind)).also { cache[kind] = it }
        worker.post { playPcm(pcm, volume) }
    }

    /**
     * 把音符序列渲染成 PCM。
     *
     * **滑音必须用相位累积**（`phase += 2πf/sr`）—— 直接代入瞬时频率 `sin(2πft)`
     * 在频率变化时会产生相位跳变，听起来是"滋"的杂音。
     */
    private fun render(notes: List<Note>): ShortArray {
        val total = notes.sumOf { SR * it.ms / 1000 }
        val buf = ShortArray(total)
        var off = 0
        for (n in notes) {
            val len = SR * n.ms / 1000
            val dur = n.ms / 1000.0
            // 起音柔坡 4ms（上一版 2ms 仍偏"啪"；再加一点让起音更圆润）
            val fadeIn = (SR * 0.004).toInt().coerceAtMost(len / 4)
            // 收尾柔坡：最后 8ms 线性落到 0，避免波形被硬切产生"咔"的尾音
            val fadeOut = (SR * 0.008).toInt().coerceAtMost(len / 3)
            var phase = 0.0
            for (i in 0 until len) {
                val t = i.toDouble() / SR
                val p = if (len > 1) i.toDouble() / (len - 1) else 0.0
                val f = if (n.sweepTo > 0.0) n.freq + (n.sweepTo - n.freq) * p else n.freq
                phase += 2 * PI * f / SR
                // 衰减常数 2.2（上一版 4.2 太陡）：尾巴拉长一点，听感更"圆"
                var env = exp(-2.2 * t / dur)
                if (i < fadeIn) env *= i.toDouble() / fadeIn
                val tail = len - i
                if (tail < fadeOut) env *= tail.toDouble() / fadeOut
                // 谐波：基波 + 三次谐波(木质)与二次谐波(按 warm 配比) —— 三次为主更柔
                val w2 = n.bright * (1 - n.warm)
                val w3 = n.bright * n.warm
                val w = sin(phase) * (1 - n.bright) + sin(2 * phase) * w2 + sin(3 * phase) * w3
                val v = (w * env * n.vol * Short.MAX_VALUE).toInt()
                buf[off + i] = v.coerceIn(-32768, 32767).toShort()
            }
            off += len
        }
        return buf
    }

    /**
     * 走 `USAGE_ASSISTANCE_SONIFICATION`（系统 UI 音轨）：不受媒体音量影响，也不会打断音乐。
     * `MODE_STATIC` + marker 回调，播完自动 release，避免泄漏。
     */
    private fun playPcm(base: ShortArray, volume: Int) {
        // 音量在这里统一施加：缓存里存的是满音量 PCM，改音量不必重新合成。
        // 用平方曲线（v*v）更接近听感 —— 线性刻度在小音量区几乎听不出变化。
        val g = (volume.coerceIn(0, 100) / 100.0).let { it * it }
        val pcm = if (g >= 1.0) base else ShortArray(base.size) { i ->
            (base[i] * g).toInt().coerceIn(-32768, 32767).toShort()
        }
        var track: AudioTrack? = null
        try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track = t
            t.write(pcm, 0, pcm.size)
            t.setNotificationMarkerPosition(pcm.size)
            t.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(tr: AudioTrack) {
                        runCatching { tr.release() }
                    }

                    override fun onPeriodicNotification(tr: AudioTrack) = Unit
                }
            )
            t.play()
        } catch (_: Throwable) {
            runCatching { track?.release() }
        }
    }
}
