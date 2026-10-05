package com.example.nfctransit.ui

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.animation.PathInterpolator
import kotlin.math.roundToInt

/**
 * 轨迹回放的触感，只有三种：
 * 1. [routeShown]：新的行程高亮出现时，一下清脆的振动；
 * 2. [highlightSweep]：停留期间线路上的高亮流光每走一趟，振动随之由强渐弱；
 * 3. [depart]：镜头开始飞行时一下短促的振动。
 *
 * 渐弱需要振幅控制（Android 8+ 且马达支持），不支持的设备没有第 2 种。
 */
class PlaybackHaptics(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }?.takeIf { it.hasVibrator() }

    var enabled = true
        set(value) {
            field = value
            if (!value) cancel()
        }

    /** 新行程高亮出现 */
    fun routeShown() {
        val v = vibrator ?: return
        val effect = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) ->
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)
                    .compose()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl() ->
                VibrationEffect.createOneShot(22, 170)
            else -> return
        }
        vibrate(effect)
    }

    /**
     * 高亮流光走一趟（[sweepMs]）：一串极短的轻触（颗粒感），而不是持续振动（持续振动再弱也像来电振铃）。
     * 强度与密度一起按 0 → 峰 → 0 变化：升、降各走一段 ease-in-out 三次贝塞尔（[BEZIER]）——
     * 两端稀疏且轻（间隔 [TICK_GAP_MAX]），中段密集且较强（间隔 [TICK_GAP_MIN]），
     * 流光头部到达线路终点（一趟的 [SWEEP_SPAN] 处）时结束。
     * 用 TOUCH 用途：ColorOS 会把每个短脉冲换成清脆的预置点振、强度随振幅变化；其他机型即为短促点振。
     */
    fun highlightSweep(sweepMs: Long) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !v.hasAmplitudeControl()) return
        val total = (sweepMs * SWEEP_SPAN).toLong()
        val timings = ArrayList<Long>()
        val amplitudes = ArrayList<Int>()
        var t = 0L
        var pendingGap = 0L
        while (t < total) {
            val p = t.toFloat() / total
            val half = if (p < 0.5f) p * 2f else (1f - p) * 2f  // 0→1→0
            val e = BEZIER.getInterpolation(half)
            val amp = (TICK_MAX * e).roundToInt()
            val gap = (TICK_GAP_MAX + (TICK_GAP_MIN - TICK_GAP_MAX) * e).toLong()
            if (amp >= TICK_MIN) {
                if (pendingGap > 0) { timings += pendingGap; amplitudes += 0 }
                timings += TICK_MS; amplitudes += amp
                pendingGap = gap - TICK_MS
            } else {
                pendingGap += gap
            }
            t += gap
        }
        if (timings.isEmpty()) return
        vibrate(VibrationEffect.createWaveform(timings.toLongArray(), amplitudes.toIntArray(), -1))
    }

    /** 镜头起飞：一下短促的振动 */
    fun depart() {
        val v = vibrator ?: return
        val effect = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl() ->
                VibrationEffect.createOneShot(PULSE_MS, PULSE_LEVEL)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            else -> return
        }
        vibrate(effect)
    }

    /** 暂停/离开页面时立即停掉仍在进行的波形 */
    fun cancel() {
        vibrator?.cancel()
    }

    private fun vibrate(effect: VibrationEffect) {
        if (!enabled) return
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // TOUCH：不受「媒体振动强度」放大；ColorOS 下短脉冲渲染为清脆的预置点振
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            v.vibrate(effect)
        }
    }

    private companion object {
        const val PULSE_MS = 28L      // 起飞脉冲
        const val PULSE_LEVEL = 255
        const val TICK_MS = 12L       // 每个轻触的时长
        const val TICK_MAX = 120      // 峰值处轻触振幅（1..255）
        const val TICK_MIN = 12       // 低于此振幅的轻触省略（两端自然淡入淡出）
        const val TICK_GAP_MAX = 110f // 两端轻触间隔（ms）
        const val TICK_GAP_MIN = 45f  // 峰值处轻触间隔（ms）
        /** 升/降曲线：cubic-bezier(0.42, 0, 0.58, 1)（ease-in-out），起止与峰值处都平滑 */
        val BEZIER = PathInterpolator(0.42f, 0f, 0.58f, 1f)
        const val SWEEP_SPAN = 0.89   // 流光头部到达终点处：中心 (1+HALF)/(1+2·HALF) ≈ 0.89
    }
}
