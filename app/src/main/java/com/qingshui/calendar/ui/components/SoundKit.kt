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
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 音效播放。
 *
 * 为什么不能只用 `View.playSoundEffect`：
 * 它走的是系统"触摸提示音"（Settings.System.SOUND_EFFECTS_ENABLED）。**很多国产 ROM（实测小米）默认把它关掉**，
 * 这时 `playSoundEffect` 会静默返回 false —— 表现就是"震动有、声音没有，把震动关了更是彻底没动静"。
 *
 * 所以这里做两手：
 *  1. 系统触摸音开着 → 用 `playSoundEffect`，音色最自然、也尊重系统设置；
 *  2. 关着（或读不到）→ 用 [synth] **现场合成**一段带指数衰减的短音，
 *     用 AudioTrack 播放，**不需要任何音频资源文件**，也不依赖系统开关。
 */
internal object SoundKit {

    private val worker: Handler by lazy {
        val t = HandlerThread("qingshui-sfx").apply { start() }
        Handler(t.looper)
    }

    fun play(view: View, kind: Fx) {
        if (systemTouchSoundOn(view.context)) {
            view.playSoundEffect(SoundEffectConstants.CLICK)
        } else {
            synth(kind)
        }
    }

    /** 翻页：左右方向用不同的音（Android 13+ 才有公开的方向音常量） */
    fun playPage(view: View, forward: Boolean) {
        if (systemTouchSoundOn(view.context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                view.playSoundEffect(
                    if (forward) SoundEffectConstants.NAVIGATION_RIGHT
                    else SoundEffectConstants.NAVIGATION_LEFT
                )
            } else {
                view.playSoundEffect(SoundEffectConstants.CLICK)
            }
        } else {
            synth(if (forward) Fx.SELECT else Fx.TICK)
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

    /** 每种反馈的音高 / 时长 / 音量，形成"轻-中-重"的音阶感 */
    private fun params(kind: Fx): Triple<Double, Int, Double> = when (kind) {
        Fx.TICK -> Triple(2380.0, 45, 0.32)
        Fx.SELECT -> Triple(1760.0, 58, 0.38)
        Fx.CONFIRM -> Triple(1320.0, 88, 0.44)
        Fx.WARN -> Triple(620.0, 150, 0.50)
        Fx.LONG_PRESS -> Triple(940.0, 70, 0.40)
    }

    /**
     * 合成一小段音：基波 + 少量二次谐波，指数衰减包络 → 听起来像柔和的"嗒"而不是刺耳的"嘀"。
     * 走 USAGE_ASSISTANCE_SONIFICATION（系统 UI 音轨），不受媒体音量影响。
     */
    private fun synth(kind: Fx) {
        val (freq, ms, vol) = params(kind)
        worker.post {
            var track: AudioTrack? = null
            try {
                val sr = 44_100
                val n = sr * ms / 1000
                val dur = ms / 1000.0
                val buf = ShortArray(n)
                for (i in 0 until n) {
                    val t = i.toDouble() / sr
                    val env = exp(-5.0 * t / dur)
                    val w = sin(2 * PI * freq * t) * 0.82 + sin(4 * PI * freq * t) * 0.18
                    buf[i] = (w * env * vol * Short.MAX_VALUE).toInt()
                        .coerceIn(-32768, 32767).toShort()
                }
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
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(n * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track = t
                t.write(buf, 0, n)
                t.setNotificationMarkerPosition(n)
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
}
