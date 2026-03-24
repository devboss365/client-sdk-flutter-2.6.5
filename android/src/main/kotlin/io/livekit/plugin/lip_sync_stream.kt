package io.livekit.plugin

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt
/**
 * 音频口型估算模块
 *
 * 从音频帧估算 ParamMouthOpenY 和 ParamMouthForm：
 * - ParamMouthOpenY: [0, 1] 张嘴幅度，基于 RMS + 平滑
 * - ParamMouthForm: [-1, 1] 嘴型变形，基于 Disney viseme 频谱匹配
 *
 * 平滑策略：
 * 1. 指数平滑 (a=0.7) 去除高频抖动
 * 2. 最小变化阈值 (s=0.03) 防止微小颤抖
 * 3. 量化到离散档位，减少中间噪声
 * 4. hold time 防抖 (80-100ms)，防止快速翻转
 */
class LipSyncEstimatorStream(
    private val sampleRate: Int = 24000,
    private val fftSize: Int = 2048,
) {
    private val hann: FloatArray
    private val freqs: FloatArray
    private val hfMask: BooleanArray
    private val lfMask: BooleanArray

    private var smoothedForm = 0.0f
    private var smoothedOpen = 0.0f
    private var outputForm = 0.0f
    private var formHoldCounter = 0

    companion object {
        val VISEME_FEATURES: Map<Int, Pair<FloatArray, Float>> = mapOf(
            1  to (floatArrayOf(0.16f, 0.13f, 0.71f) to 0.0f),   // p/b/m 闭嘴，中性
            2  to (floatArrayOf(0.05f, 0.07f, 0.93f) to -0.6f),   // w 圆唇，生气
            3  to (floatArrayOf(0.12f, 0.13f, 0.64f) to -0.3f),   // r 稍圆，略生气
            4  to (floatArrayOf(0.33f, 0.50f, 0.36f) to 0.0f),    // f/v 中性
            5  to (floatArrayOf(0.43f, 0.40f, 0.26f) to 0.3f),    // th 微笑
            6  to (floatArrayOf(0.29f, 0.23f, 0.43f) to 0.0f),    // l 中性
            7  to (floatArrayOf(0.48f, 0.60f, 0.26f) to 0.3f),    // d/t/z/s/n 微笑
            8  to (floatArrayOf(0.62f, 0.83f, 0.17f) to 0.3f),    // ʃ/tʃ/dʒ/ʒ 微笑
            9  to (floatArrayOf(0.24f, 0.27f, 0.50f) to 0.0f),    // j/g/k 中性
            10 to (floatArrayOf(0.90f, 0.20f, 0.11f) to 1.0f),    // i/ɪ/e 微笑（扁嘴最大）
            11 to (floatArrayOf(0.10f, 0.07f, 0.86f) to -0.6f),   // o/u 圆唇，生气
            12 to (floatArrayOf(0.33f, 0.20f, 0.50f) to 0.5f),    // a 大开口，微笑
        )

        private val WEIGHTS = floatArrayOf(0.5f, 0.20f, 0.30f)
        const val CENTROID_MIN = 400.0
        const val CENTROID_MAX = 2500.0
        const val HF_FREQ = 4000.0
        const val LF_FREQ = 500.0
        const val HF_MAX = 0.30
        const val LF_MAX = 0.70

        // form 平滑参数
        const val FORM_ALPHA = 0.7f
        const val FORM_MIN_DELTA = 0.03f
        val FORM_QUANTIZE_LEVELS = floatArrayOf(-1.0f, -0.6f, -0.3f, 0.0f, 0.3f, 0.5f, 1.0f)
        const val FORM_HOLD_FRAMES = 5 // 60fps × 5 ≈ 83ms

        // open_y 平滑参数
        const val OPEN_ALPHA_UP = 0.3f
        const val OPEN_ALPHA_DOWN = 0.25f
        const val OPEN_MIN_DELTA = 0.01f
    }

    init {
        hann = FloatArray(fftSize) { i ->
            val n = fftSize - 1
            (0.5 * (1.0 - cos(2.0 * PI * i / n))).toFloat()
        }
        freqs = FloatArray(fftSize / 2 + 1) { i ->
            (i.toFloat() * sampleRate.toFloat() / fftSize.toFloat())
        }
        hfMask = BooleanArray(freqs.size) { i -> freqs[i] >= HF_FREQ }
        lfMask = BooleanArray(freqs.size) { i -> freqs[i] <= LF_FREQ }
    }

    /**
     * 从单声道 PCM 片段估算口型参数。
     *
     * @param pcmMono 单声道音频数据（FloatArray）
     * @return Pair(openY, mouthForm): ParamMouthOpenY [0,1], ParamMouthForm [-1,1]
     */
    fun estimate(pcmMono: FloatArray): Pair<Float, Float> {
        val rms = computeRms(pcmMono)
        val openY = estimateOpen(rms)
        val mouthForm = estimateForm(pcmMono, rms)
        return Pair(openY, mouthForm)
    }

    private fun computeRms(pcmMono: FloatArray): Float {
        var sum = 0.0
        for (s in pcmMono) {
            sum += s.toDouble() * s.toDouble()
        }
        return sqrt(sum / pcmMono.size).toFloat()
    }

    private fun estimateOpen(rms: Float): Float {
        // 1. 提高倍率至 6.0，降低指数至 0.7f
        // 使用 coerceIn 代替 Swift 的 max(min(...))，这是 Kotlin 更地道的写法
        val rawOpen = (rms * 6.0f).coerceIn(0.0f, 1.0f).toDouble().pow(0.7).toFloat()

        val alpha: Float = if (abs(rawOpen - smoothedOpen) < OPEN_MIN_DELTA) {
            0.0f
        } else {
            // 2. 这里的速度：张嘴快 (0.6f)，闭嘴慢 (0.2f)
            if (rawOpen > smoothedOpen) 0.6f else 0.2f
        }

        if (alpha > 0.0f) {
            smoothedOpen = smoothedOpen * (1.0f - alpha) + rawOpen * alpha
        }

        // 四舍五入保留四位小数
        return (round(smoothedOpen * 10000) / 10000.0f)
    }

    private fun estimateForm(pcmMono: FloatArray, rms: Float): Float {
        val targetForm: Float

        if (rms < 0.02f) {
            targetForm = 0.0f
        } else {
            val features = extractFeatures(pcmMono)
            val (f1, f2, w1) = findClosestVisemes(features)
            var raw = f1 * w1 + f2 * (1.0f - w1)
            raw = raw.coerceIn(-1.0f, 1.0f)
            targetForm = snapToLevel(raw)
        }

        // 指数平滑：v_t = a * v_{t-1} + (1-a) * raw
        var smoothed = FORM_ALPHA * smoothedForm + (1.0f - FORM_ALPHA) * targetForm

        // 最小变化阈值
        if (abs(smoothed - outputForm) < FORM_MIN_DELTA) {
            smoothed = outputForm
        }

        // hold time 防抖：当 viseme 方向发生翻转时，保持当前值几帧
        if (formHoldCounter > 0) {
            formHoldCounter--
            smoothed = outputForm
        } else if (outputForm != 0.0f && signChanged(outputForm, smoothed)) {
            formHoldCounter = FORM_HOLD_FRAMES
            smoothed = outputForm
        }

        smoothedForm = smoothed
        outputForm = smoothed

        return (Math.round(outputForm * 10000) / 10000.0f)
    }

    private fun signChanged(oldVal: Float, newVal: Float): Boolean {
        if (abs(oldVal) < 0.1f || abs(newVal) < 0.1f) return false
        return (oldVal > 0) != (newVal > 0)
    }

    private fun snapToLevel(value: Float): Float {
        return FORM_QUANTIZE_LEVELS.minByOrNull { abs(it - value) } ?: 0.0f
    }

    private fun extractFeatures(pcmMono: FloatArray): FloatArray {
        var samples = pcmMono
        if (samples.size < fftSize) {
            samples = FloatArray(fftSize) { i ->
                if (i < pcmMono.size) pcmMono[i] else 0.0f
            }
        }

        val windowed = FloatArray(fftSize) { i -> samples[i] * hann[i] }
        val spectrum = rfft(windowed)
        val power = FloatArray(spectrum.size) { i -> spectrum[i] * spectrum[i] }

        var totalPower = 0.0
        for (p in power) totalPower += p.toDouble()

        if (totalPower < 1e-10) return floatArrayOf(0.0f, 0.0f, 0.0f)

        var centroid = 0.0
        for (i in freqs.indices) {
            centroid += freqs[i].toDouble() * power[i].toDouble()
        }
        centroid /= totalPower

        val centroidNorm = ((centroid - CENTROID_MIN) / (CENTROID_MAX - CENTROID_MIN))
            .coerceIn(0.0, 1.0).toFloat()

        var hfPower = 0.0
        var lfPower = 0.0
        for (i in hfMask.indices) {
            if (hfMask[i]) hfPower += power[i].toDouble()
            if (lfMask[i]) lfPower += power[i].toDouble()
        }

        val hfNorm = (hfPower / totalPower / HF_MAX).coerceIn(0.0, 1.0).toFloat()
        val lfNorm = (lfPower / totalPower / LF_MAX).coerceIn(0.0, 1.0).toFloat()

        return floatArrayOf(centroidNorm, hfNorm, lfNorm)
    }

    private data class VisemeDistance(val dist: Float, val form: Float)

    private fun findClosestVisemes(features: FloatArray): Triple<Float, Float, Float> {
        val dists = mutableListOf<VisemeDistance>()

        for ((_, pair) in VISEME_FEATURES) {
            val (ref, form) = pair
            var dist = 0.0
            for (i in WEIGHTS.indices) {
                val diff = features[i].toDouble() - ref[i].toDouble()
                dist += WEIGHTS[i].toDouble() * diff * diff
            }
            dists.add(VisemeDistance(sqrt(dist).toFloat(), form))
        }

        dists.sortBy { it.dist }

        val d1 = dists[0]
        val d2 = dists[1]
        val total = d1.dist + d2.dist

        if (total < 1e-8f) return Triple(d1.form, d2.form, 0.5f)

        val w1 = 1.0f - d1.dist / total
        return Triple(d1.form, d2.form, w1)
    }

    fun reset() {
        smoothedForm = 0.0f
        smoothedOpen = 0.0f
        outputForm = 0.0f
        formHoldCounter = 0
    }
}

/** 简易 FFT (rfft) - 用于将时域信号转换为频域 */
private fun rfft(input: FloatArray): FloatArray {
    val n = input.size
    if (n == 0) return floatArrayOf()

    // 调用 JRE 的 FFT 实现
    val real = DoubleArray(n)
    val imag = DoubleArray(n)
    for (i in input.indices) real[i] = input[i].toDouble()

    fft(real, imag)

    // rfft 输出：前 n/2+1 个复数的幅度
    val halfN = n / 2 + 1
    val result = FloatArray(halfN)
    for (i in 0 until halfN) {
        result[i] = sqrt(real[i] * real[i] + imag[i] * imag[i]).toFloat()
    }
    return result
}

/** Cooley-Tukey 基2 FFT */
private fun fft(real: DoubleArray, imag: DoubleArray) {
    val n = real.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            var tmp = real[i]; real[i] = real[j]; real[j] = tmp
            tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
        }
    }

    var len = 2
    while (len <= n) {
        val halfLen = len / 2
        val angle = -2.0 * PI / len
        val wReal = kotlin.math.cos(angle)
        val wImag = kotlin.math.sin(angle)
        var i = 0
        while (i < n) {
            var curReal = 1.0
            var curImag = 0.0
            for (k in 0 until halfLen) {
                val uReal = real[i + k]
                val uImag = imag[i + k]
                val vReal = real[i + k + halfLen] * curReal - imag[i + k + halfLen] * curImag
                val vImag = real[i + k + halfLen] * curImag + imag[i + k + halfLen] * curReal
                real[i + k] = uReal + vReal
                imag[i + k] = uImag + vImag
                real[i + k + halfLen] = uReal - vReal
                imag[i + k + halfLen] = uImag - vImag
                val newCurReal = curReal * wReal - curImag * wImag
                curImag = curReal * wImag + curImag * wReal
                curReal = newCurReal
            }
            i += len
        }
        len *= 2
    }
}

private const val PI = 3.14159265358979323846
private fun cos(x: Double): Double = kotlin.math.cos(x)
private fun sin(x: Double): Double = kotlin.math.sin(x)
