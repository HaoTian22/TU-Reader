package com.example.nfctransit.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * 轨迹回放音效，与 [PlaybackHaptics] 的三个节点一一对应，全部由代码实时合成（无音频资源）：
 * 1. [routeShown]：新行程高亮出现——一声柔和的确认音（A4）；
 * 2. [highlightSweep]：流光走一趟——一句连贯的连奏旋律：D 大调五声音阶逐级上行（不滑音），相邻音交叉淡入淡出
 *    连成一体，底下垫 D3·A3 持续音；强弱与触感同一条 0 → 峰 → 0 曲线，由左向右；
 * 3. [depart]：镜头飞行——悬而未决的开放五度和弦（D3·A3·E4·B4）逐个音淡入、缓缓展开，
 *    像新画面即将铺开的期待感；不滑音（避免引擎感），到达时由 A4 解决。
 *
 * 流光音效只属于当前激活的线路：线路失去激活（起飞/切换/暂停）时用 [stopSweep] 立即淡出。
 *
 * 合成在后台单线程完成，结果以 MODE_STATIC 的 [AudioTrack] 播放；走媒体音量。
 */
class PlaybackSound {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val active = mutableListOf<AudioTrack>()
    private val sweepTracks = mutableSetOf<AudioTrack>()   // active 中属于流光的音轨
    @Volatile private var generation = 0
    @Volatile private var sweepGeneration = 0   // stopSweep 后，尚未开始播放的流光音效作废

    // 仅在 executor 线程访问
    private var chimePcm: ShortArray? = null
    private val sparkleCache = HashMap<Long, ShortArray>()
    private val waitCache = HashMap<Long, ShortArray>()

    var enabled = true
        set(value) {
            field = value
            if (!value) cancel()
        }

    fun routeShown() = play { chimePcm ?: chime().also { chimePcm = it } }

    fun highlightSweep(sweepMs: Long) = play(sweep = true) { sparkleCache.getOrPut(sweepMs) { sparkle(sweepMs) } }

    fun depart(flyMs: Long) = play { waitCache.getOrPut(flyMs) { waiting(flyMs) } }

    /** 当前线路失去激活：正在播放的流光音效快速淡出（音轨音量带内部渐变，先静音再停，避免爆音） */
    fun stopSweep() {
        sweepGeneration++
        val tracks = synchronized(active) {
            sweepTracks.toList().also { active.removeAll(it); sweepTracks.clear() }
        }
        fadeOutAndRelease(tracks)
    }

    /** 先静音（音轨音量自带短渐变）再停止释放，避免直接 stop 的"咔"声与戛然而止 */
    private fun fadeOutAndRelease(tracks: List<AudioTrack>) {
        if (tracks.isEmpty()) return
        tracks.forEach { runCatching { it.setVolume(0f) } }
        main.postDelayed({ tracks.forEach { runCatching { it.stop() }; it.release() } }, 60)
    }

    /** 暂停/离开页面：停掉正在播放与尚未开始的音效 */
    fun cancel() {
        generation++
        val tracks = synchronized(active) {
            active.toList().also { active.clear(); sweepTracks.clear() }
        }
        fadeOutAndRelease(tracks)
    }

    fun release() {
        cancel()
        executor.shutdownNow()
    }

    private fun play(sweep: Boolean = false, build: () -> ShortArray) {
        if (!enabled || executor.isShutdown) return
        val gen = generation
        val sweepGen = sweepGeneration
        runCatching {
            executor.execute {
                if (gen != generation) return@execute
                val pcm = build()
                val track = runCatching { newTrack(pcm) }.getOrNull() ?: return@execute
                synchronized(active) {
                    if (gen != generation || (sweep && sweepGen != sweepGeneration)) {
                        track.release(); return@execute
                    }
                    active += track
                    if (sweep) sweepTracks += track
                }
                runCatching { track.play() }
                val durationMs = pcm.size / CHANNELS * 1000L / RATE
                main.postDelayed({
                    synchronized(active) {
                        sweepTracks.remove(track)
                        if (active.remove(track)) track.release()
                    }
                }, durationMs + RELEASE_SLACK_MS)
            }
        }
    }

    private fun newTrack(pcm: ShortArray): AudioTrack? {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        // MODE_STATIC 写入数据前状态为 STATE_NO_STATIC_DATA，写完才是 STATE_INITIALIZED
        if (track.write(pcm, 0, pcm.size) != pcm.size || track.state != AudioTrack.STATE_INITIALIZED) {
            track.release(); return null
        }
        return track
    }

    // ── 合成 ──

    /*
     * 音色方向：柔和的"车机"科技感（参考特斯拉 UI 提示音）——以纯正弦为主、圆润起音、轻微滑音、
     * 中低音区、无噪声嘶声，最后整体过一道低通，去掉一切刺耳的高频。
     */

    /** 新行程出现：一声柔和的确认音（A4），带一点从上滑落的音头，叠低八度增加厚度 */
    private fun chime(): ShortArray {
        val buf = Mix(900)
        softTone(buf, 0, 440.00, 0.08, 0.5f)
        return buf.toPcm(cutoffHz = 1500.0)
    }

    private fun softTone(buf: Mix, startMs: Int, freq: Double, amp: Double, pan: Float) {
        val start = startMs * RATE / 1000
        var phase = 0.0
        for (i in 0 until buf.frames - start) {
            val t = i.toDouble() / RATE
            val glide = 1 + 0.015 * exp(-t / 0.012)          // 音头从高 1.5% 处滑落到位
            phase += 2 * PI * freq * glide / RATE
            val attack = if (t < 0.025) 0.5 - 0.5 * cos(PI * t / 0.025) else 1.0
            val env = attack * exp(-t / 0.18)
            val s = sin(phase) + 0.3 * sin(phase / 2)
            buf.add(start + i, s * amp * env, pan)
        }
    }

    /**
     * 流光：连奏旋律。[LEGATO_MELODY] 各音等分流光头部走到终点的时间，每个音持续 [LEGATO_OVERLAP] 个步长、
     * 余弦淡入淡出，与后一音交叉重叠，音与音之间没有空隙也没有滑音；底下 D3·A3 持续音托底。
     * 音色为正弦 + 少量 2、3 次谐波（柔和的长笛/风琴感），每个音两条微失谐正弦带一点合唱感。
     */
    private fun sparkle(sweepMs: Long): ShortArray {
        val buf = Mix((sweepMs * SWEEP_SPAN).toInt())
        val total = buf.frames
        val step = total.toDouble() / LEGATO_MELODY.size
        // 旋律
        for ((k, freq) in LEGATO_MELODY.withIndex()) {
            val start = (k * step).toInt()
            val len = minOf((step * LEGATO_OVERLAP).toInt(), total - start)
            var ph1 = 0.0
            var ph2 = 0.0
            for (i in 0 until len) {
                val q = i.toDouble() / len
                val noteEnv = sin(PI * q)                              // 淡入 → 淡出（与相邻音交叉）
                ph1 += 2 * PI * freq * 0.9995 / RATE
                ph2 += 2 * PI * freq * 1.0005 / RATE
                val tone = 0.5 * (voice(ph1) + voice(ph2))
                val p = (start + i).toDouble() / total
                buf.add(start + i, tone * 0.05 * noteEnv * sweepEnv(p), (0.25 + 0.5 * p).toFloat())
            }
        }
        // 持续音
        var d1 = 0.0
        var d2 = 0.0
        for (i in 0 until total) {
            val p = i.toDouble() / total
            d1 += 2 * PI * 146.83 / RATE
            d2 += 2 * PI * 220.0 / RATE
            buf.add(i, (sin(d1) + 0.6 * sin(d2)) * 0.022 * sweepEnv(p), 0.5f)
        }
        return buf.toPcm(cutoffHz = 1600.0)
    }

    /** 柔和音色：正弦 + 少量 2、3 次谐波 */
    private fun voice(phase: Double) = sin(phase) + 0.25 * sin(2 * phase) + 0.08 * sin(3 * phase)

    /** 流光一趟的整体强弱：smoothstep 0 → 1 → 0 */
    private fun sweepEnv(p: Double): Double {
        val half = if (p < 0.5) p * 2 else (1 - p) * 2
        return half * half * (3 - 2 * half)
    }

    /**
     * 飞行等待：开放五度和弦（D3 → A3 → E4 → B4）按飞行进度依次淡入，像画面一层层展开；
     * 每个音由两条相差约 2 音分的正弦组成，缓慢拍频产生柔和的微光。
     * 整体渐强到飞行的 85% 处，之后按指数自然衰减，余音延续到落地之后 [WAIT_TAIL_MS]，
     * 垫在到达的 A4 下面一起收尾——不会在落地瞬间被截断（短飞行时原先的 20% 收尾只有一两百毫秒，听起来像戛然而止）。
     */
    private fun waiting(flyMs: Long): ShortArray {
        val fly = flyMs.coerceAtLeast(1L) / 1000.0
        val buf = Mix((flyMs + WAIT_TAIL_MS).toInt())
        val peakAt = fly * 0.85
        val tailFade = 0.3   // 最后 0.3s 余弦收到 0，保证结尾绝对干净
        val totalSec = buf.frames.toDouble() / RATE
        val phases = DoubleArray(BLOOM_CHORD.size * 2)
        for (i in 0 until buf.frames) {
            val t = i.toDouble() / RATE
            val env = if (t < peakAt) 0.5 - 0.5 * cos(PI * t / peakAt)
                else exp(-(t - peakAt) / WAIT_DECAY_S)
            val end = ((totalSec - t) / tailFade).coerceIn(0.0, 1.0)
            var s = 0.0
            for ((k, freq) in BLOOM_CHORD.withIndex()) {
                val entry = k * 0.18 * fly                      // 第 k 个音在飞行的 18%·k 处开始淡入
                val q = ((t - entry) / (0.3 * fly)).coerceIn(0.0, 1.0)
                if (q <= 0.0) continue
                val fadeIn = 0.5 - 0.5 * cos(PI * q)
                for (d in 0..1) {
                    val idx = k * 2 + d
                    phases[idx] += 2 * PI * freq * (if (d == 0) 0.9994 else 1.0006) / RATE
                    s += sin(phases[idx]) * 0.5 * fadeIn * BLOOM_GAIN[k]
                }
            }
            buf.add(i, s * 0.055 * env * (0.5 - 0.5 * cos(PI * end)), 0.5f)
        }
        return buf.toPcm(cutoffHz = 1300.0)
    }

    /** 立体声浮点混音缓冲 */
    private class Mix(durMs: Int) {
        val frames = durMs * RATE / 1000
        private val l = DoubleArray(frames)
        private val r = DoubleArray(frames)

        fun add(i: Int, s: Double, pan: Float) {
            if (i !in 0 until frames) return
            // 等功率声像
            l[i] += s * sqrt(1.0 - pan)
            r[i] += s * sqrt(pan.toDouble())
        }

        /** 输出前过一道一阶低通（[cutoffHz]）去掉刺耳高频，结尾 5ms 淡出避免爆音 */
        fun toPcm(cutoffHz: Double): ShortArray {
            val out = ShortArray(frames * CHANNELS)
            val k = 1 - exp(-2 * PI * cutoffHz / RATE)
            var fl = 0.0
            var fr = 0.0
            val fade = (RATE * 0.005).toInt()
            for (i in 0 until frames) {
                fl += k * (l[i] - fl)
                fr += k * (r[i] - fr)
                val g = if (i >= frames - fade) (frames - i).toDouble() / fade else 1.0
                out[2 * i] = toShort(fl * g)
                out[2 * i + 1] = toShort(fr * g)
            }
            return out
        }

        private fun toShort(v: Double): Short {
            return (tanh(v) * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private companion object {
        const val RATE = 44100
        const val CHANNELS = 2
        const val SWEEP_SPAN = 0.89   // 流光头部到达终点处（同 PlaybackHaptics）
        /** 流光连奏旋律：D 大调五声音阶 A3 → A4 逐级上行 */
        val LEGATO_MELODY = doubleArrayOf(220.0, 246.94, 293.66, 329.63, 369.99, 440.0)
        const val LEGATO_OVERLAP = 1.6   // 每个音持续的步长数：>1 才能与后一音交叉连成一体
        /** 等待和弦：开放五度叠置，悬而未决 */
        val BLOOM_CHORD = doubleArrayOf(146.83, 220.0, 329.63, 493.88)
        val BLOOM_GAIN = doubleArrayOf(1.0, 0.8, 0.55, 0.3)
        /** 播完后再保留的时间：蓝牙 A2DP 输出延迟可达 1~2s，过早 release 会把还没出声的音效截掉 */
        const val RELEASE_SLACK_MS = 3000L
        const val WAIT_TAIL_MS = 1400L   // 等待和弦在落地后的余音长度
        const val WAIT_DECAY_S = 0.45    // 余音指数衰减时间常数
    }
}
