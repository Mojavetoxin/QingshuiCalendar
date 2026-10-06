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
 * 音效设计原则（对着成品 App 来的）：
 *  - **按语义配谱**，不是所有操作同一声"嘀"：正向操作音高**上行**（听起来"成了"），
 *    负向/警示**下行**（听起来"不行"），展开/收起、左右翻页用**滑音**表达方向。
 *  - **轻重有序**：轻点最短最脆（42ms），确认/完成更长更饱满（多音）。
 *  - **音色可调**：[Note.bright] 控制二次谐波比例 —— 大则"脆"，小则"闷"，
 *    所以警示音听起来沉、完成音听起来亮。
 *  - **PCM 缓存**：同一音效只合成一次（[cache]），点下去更快出声。
 *  - **去爆音**：每个音前 2ms 淡入，避免起音的"啪"声。
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
     * @param bright  二次谐波比例（0..1）：越大越"脆"，越小越"闷"
     */
    private data class Note(
        val freq: Double,
        val ms: Int,
        val vol: Double,
        val sweepTo: Double = 0.0,
        val bright: Double = 0.20
    )

    /**
     * 音效谱。按语义分档，正向上行、负向下行、方向性动作用滑音。
     */
    private fun score(kind: Fx): List<Note> = when (kind) {
        // 轻点：一声极短的脆响（选日期、切标签、开关）
        Fx.TICK -> listOf(Note(2380.0, 42, 0.28, bright = 0.28))

        // 选择：比轻点低一点、稍长（翻页、选中、展开）
        Fx.SELECT -> listOf(Note(1760.0, 56, 0.34))

        // 确认：两音上行（保存、新建）
        Fx.CONFIRM -> listOf(
            Note(1480.0, 44, 0.36),
            Note(1980.0, 62, 0.32)
        )

        // 完成：三音明亮上行 —— 勾选日程完成，要有"搞定"的爽感
        Fx.SUCCESS -> listOf(
            Note(1568.0, 44, 0.32, bright = 0.26),
            Note(1976.0, 44, 0.32, bright = 0.26),
            Note(2637.0, 78, 0.30, bright = 0.30)
        )

        // 长按：偏低、稍闷
        Fx.LONG_PRESS -> listOf(Note(880.0, 64, 0.34, bright = 0.12))

        // 警示：低音下行两下（删除、清空）
        Fx.WARN -> listOf(
            Note(560.0, 88, 0.44, bright = 0.10),
            Note(440.0, 118, 0.42, bright = 0.10)
        )

        // 失败：更低更闷的下行两音（解析失败、导入出错）
        Fx.ERROR -> listOf(
            Note(392.0, 108, 0.42, bright = 0.08),
            Note(294.0, 168, 0.40, bright = 0.08)
        )

        // 展开：上行滑音
        Fx.EXPAND -> listOf(Note(620.0, 88, 0.30, sweepTo = 1040.0, bright = 0.18))

        // 收起：下行滑音
        Fx.COLLAPSE -> listOf(Note(1040.0, 88, 0.30, sweepTo = 620.0, bright = 0.18))

        // 向后翻页（下一月/下一天）：向右上滑
        Fx.SLIDE_FWD -> listOf(Note(760.0, 84, 0.28, sweepTo = 1300.0, bright = 0.16))

        // 向前翻页：向左下滑
        Fx.SLIDE_BACK -> listOf(Note(1300.0, 84, 0.28, sweepTo = 760.0, bright = 0.16))
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
            val fadeIn = (SR * 0.002).toInt().coerceAtMost(len / 4)
            var phase = 0.0
            for (i in 0 until len) {
                val t = i.toDouble() / SR
                val p = if (len > 1) i.toDouble() / (len - 1) else 0.0
                val f = if (n.sweepTo > 0.0) n.freq + (n.sweepTo - n.freq) * p else n.freq
                phase += 2 * PI * f / SR
                var env = exp(-4.2 * t / dur)
                if (i < fadeIn) env *= i.toDouble() / fadeIn
                val w = sin(phase) * (1 - n.bright) + sin(2 * phase) * n.bright
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
