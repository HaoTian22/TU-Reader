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
        val timings = ArrayList<Long>()
        val amplitudes = ArrayList<Int>()
        var end = 0L
        for ((t, e) in sweepTicks(sweepMs)) {
            if (t > end) { timings += t - end; amplitudes += 0 }
            timings += TICK_MS; amplitudes += (TICK_MAX * e).roundToInt()
            end = t + TICK_MS
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

    companion object {
        /**
         * 流光一趟内的轻触时刻（ms）与强度（0..1）。
         * 强度低于 [TICK_MIN]/[TICK_MAX] 的轻触省略。
         */
        fun sweepTicks(sweepMs: Long): List<Pair<Long, Float>> {
            val total = (sweepMs * SWEEP_SPAN).toLong()
            val ticks = ArrayList<Pair<Long, Float>>()
            var t = 0L
            while (t < total) {
                val p = t.toFloat() / total
                val half = if (p < 0.5f) p * 2f else (1f - p) * 2f  // 0→1→0
                val e = BEZIER.getInterpolation(half)
                if ((TICK_MAX * e).roundToInt() >= TICK_MIN) ticks += t to e
                t += (TICK_GAP_MAX + (TICK_GAP_MIN - TICK_GAP_MAX) * e).toLong()
            }
            return ticks
        }

        private const val PULSE_MS = 28L      // 起飞脉冲
        private const val PULSE_LEVEL = 255
        private const val TICK_MS = 12L       // 每个轻触的时长
        private const val TICK_MAX = 120      // 峰值处轻触振幅（1..255）
        private const val TICK_MIN = 12       // 低于此振幅的轻触省略（两端自然淡入淡出）
        private const val TICK_GAP_MAX = 110f // 两端轻触间隔（ms）
        private const val TICK_GAP_MIN = 45f  // 峰值处轻触间隔（ms）
        /** 升/降曲线：cubic-bezier(0.42, 0, 0.58, 1)（ease-in-out），起止与峰值处都平滑 */
        private val BEZIER = PathInterpolator(0.42f, 0f, 0.58f, 1f)
        private const val SWEEP_SPAN = 0.89   // 流光头部到达终点处：中心 (1+HALF)/(1+2·HALF) ≈ 0.89
    }
}
