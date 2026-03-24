/*
 * Copyright 2024 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Originally adapted from: https://github.com/dzolnai/ExoVisualizer
 *
 * MIT License
 *
 * Copyright (c) 2019 Dániel Zolnai
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.livekit.plugin

import android.media.AudioTrack
import com.paramsen.noise.Noise
import java.lang.Math.abs
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.pow


/**
 * A Fast Fourier Transform analyzer for audio bytes.
 *
 * Use [queueInput] to add audio bytes, and collect on [fftFlow]
 * to receive the analyzed frequencies.
 */
class FFTAudioAnalyzer2 {

    companion object {
        const val SAMPLE_SIZE = 512
        private val EMPTY_BUFFER = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

        // Extra size next in addition to the AudioTrack buffer size
        private const val BUFFER_EXTRA_SIZE = SAMPLE_SIZE * 8

        // Size of short in bytes.
        private const val SHORT_SIZE = 2
    }

    private val isActive: Boolean
        get() = noise != null

    private var noise: Noise? = null
    private lateinit var inputAudioFormat: AudioFormat2

    private var audioTrackBufferSize = 0

    private var fftBuffer: ByteBuffer = EMPTY_BUFFER
    private lateinit var srcBuffer: ByteBuffer
    private var srcBufferPosition = 0
    private val tempShortArray = ShortArray(SAMPLE_SIZE)
    private val src = FloatArray(SAMPLE_SIZE)

    // 在类中定义平滑变量
    public var smoothedOpenY = 0f
    public var smoothedForm = 0f
    public val smoothingFactor = 0.2f // 越小越平滑，越大越跟手

    private val attackFactor = 0.6f   // 开口极快，增加爆发感
    private val releaseFactor = 0.15f // 闭口稍缓但要有力



    /**
     * A flow of frequencies for the audio bytes given through [queueInput].
     */
    var fft: FloatArray? = null
        private set

    fun configure(inputAudioFormat: AudioFormat2) {
        this.inputAudioFormat = inputAudioFormat

        noise = Noise.real(SAMPLE_SIZE)

        audioTrackBufferSize = getDefaultBufferSizeInBytes(inputAudioFormat)

        srcBuffer = ByteBuffer.allocate(audioTrackBufferSize + BUFFER_EXTRA_SIZE)
    }

    fun release() {
        noise?.close()
        noise = null
    }

    // 定义频段索引（基于 48kHz, 512 Sample, 每个 Bin 约 94Hz）
    private val BINS_LOW = 2..8     // ~180Hz - 750Hz (F1 主要区域)
    private val BINS_MID = 9..16    // ~850Hz - 1500Hz
    private val BINS_HIGH = 17..32  // ~1600Hz - 3000Hz (F2 主要区域)
    // 1. 在类成员变量里，调低保底值
    private var maxEnergyMovingAvg = 0.005f // 调得非常低，保证初始灵敏度
    private val decayRate2 = 0.002f    // 衰减变慢，保持稳定性

    fun getVowelParameters(): Map<String, Float> {
        val currentFft = fft ?: return emptyMap()

        // 1. 计算三个关键频段的平均能量
        val lowEnergy = calcAverageMag(currentFft, BINS_LOW)
        val midEnergy = calcAverageMag(currentFft, BINS_MID)
        val highEnergy = calcAverageMag(currentFft, BINS_HIGH)

        val currentTotal = lowEnergy + midEnergy + highEnergy

        // 2. 动态最大值跟踪 (AGC 自动增益控制)
        if (currentTotal > maxEnergyMovingAvg) {
            // 快速跟进峰值
            maxEnergyMovingAvg = currentTotal
        } else {
            // 缓慢衰减，防止被一个瞬间的大声顶死
            maxEnergyMovingAvg = max(0.002f, maxEnergyMovingAvg - decayRate2)
        }

        // 3. 极低门限 (Noise Floor)
        // 如果能量不到最大值的 5%，强制闭嘴
        if (currentTotal < maxEnergyMovingAvg * 0.05f || currentTotal < 0.001f) {
            return mapOf("openY" to 0f, "form" to 0f)
        }

        // 4. 映射逻辑：直接归一化并加压
        // 使用 (currentTotal / maxEnergyMovingAvg) 得到 0.0 - 1.0 的比例
        val ratio = (currentTotal / maxEnergyMovingAvg).coerceIn(0f, 1.0f)

        // 关键修正：给一个基础增益 (1.5f)，让中等音量也能张得开
        // 使用 1.2 次方：既有曲线感，又不会压制太狠，保证张开度
        val openY = (ratio * 2.5f).pow(1.1f).coerceIn(0f, 1.0f)

        // 5. 计算 Form (口型横向变化)
        val lowRatio = if (currentTotal > 0) lowEnergy / currentTotal else 0f
        val highRatio = if (currentTotal > 0) highEnergy / currentTotal else 0f
        val form = ((highRatio - lowRatio) * 2.0f).coerceIn(-1.0f, 1.0f)

        return mapOf("openY" to openY.coerceIn(0f, 1.2f), "form" to form)
    }

//    // 在类成员变量中增加动态基准跟踪
//    private var maxEnergyMovingAvg = 0.05f
//    private var decayRate2 = 0.003f // 基准衰减，用于自动适配小声环境
//
//    fun getVowelParameters(): Map<String, Float> {
//        val currentFft = fft ?: return emptyMap()
//
//        // 1. 计算三个关键频段的平均能量
//        val lowEnergy = calcAverageMag(currentFft, BINS_LOW)
//        val midEnergy = calcAverageMag(currentFft, BINS_MID)
//        val highEnergy = calcAverageMag(currentFft, BINS_HIGH)
//
//        // 2. 动态增益控制 (核心：防止持续张大嘴)
//        val currentTotal = lowEnergy + midEnergy + highEnergy
//
//        if (currentTotal > maxEnergyMovingAvg) {
//            maxEnergyMovingAvg = currentTotal // 快速跟进峰值
//        } else {
//            // 缓慢下沉，保证后续灵敏度
//            maxEnergyMovingAvg = max(0.01f, maxEnergyMovingAvg - decayRate2)
//        }
//
//        // 3. 噪声门限：低于基准 10% 视为静音
//        if (currentTotal < maxEnergyMovingAvg * 0.1f || currentTotal < 0.0015f) {
//            return mapOf("openY" to 0f, "form" to 0f)
//        }
//
//        // 4. 计算比例 (用于判断口型 Form)
//        val lowRatio = lowEnergy / currentTotal
//        val highRatio = highEnergy / currentTotal
//
//        // 5. 核心计算：OpenY (张合度)
//        // 改用“当前总能量 / 近期最大能量”的比例，而非固定倍数
//        val baseOpenRatio = (currentTotal / maxEnergyMovingAvg).coerceIn(0f, 1f)
//
//        // 引入二次幂映射：增加“爆发感”，解决持续张嘴问题
//        // 幂次越高，嘴巴在小声时闭得越严，只有大声才完全张开
//        val openY = baseOpenY(baseOpenRatio).pow(1.8f)
//
//        // 6. 核心计算：Form (口型横向变化)
//        // 保持你的逻辑：高频多偏扁(1.0)，低频多偏圆(-1.0)
//        val form = ((highRatio - lowRatio) * 2.0f).coerceIn(-1.0f, 1.0f)
//
//        return mapOf("openY" to openY.coerceIn(0f, 1.2f), "form" to form)
//    }

    // 辅助函数，给 OpenY 一个基础增益，保证说话时能张得开
    private fun baseOpenY(ratio: Float): Float {
        // 这是一个简单的增益曲线，让中等音量也能达到较好的张开度
        return (ratio * 1.5f).coerceIn(0f, 1f)
    }

    private fun calcAverageMag(fft: FloatArray, range: IntRange): Float {
        var sum = 0f
        for (i in range.first..range.last) {
            val r = fft[i * 2]
            val im = fft[i * 2 + 1]
            sum += sqrt(r * r + im * im)
        }
        return sum / (range.last - range.first + 1)
    }

    /**
     * Add audio bytes to be processed.
     */
    fun queueInput(inputBuffer: ByteBuffer) {
        if (!isActive) {
            return
        }
        var position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val frameCount = (limit - position) / (SHORT_SIZE * inputAudioFormat.numberOfChannels)
        val singleChannelOutputSize = frameCount * SHORT_SIZE

        // Setup buffer
        if (fftBuffer.capacity() < singleChannelOutputSize) {
            fftBuffer =
                ByteBuffer.allocateDirect(singleChannelOutputSize).order(ByteOrder.nativeOrder())
        } else {
            fftBuffer.clear()
        }

        // Process inputBuffer
        while (position < limit) {
            var summedUp: Short = 0
            for (channelIndex in 0 until inputAudioFormat.numberOfChannels) {
                if( channelIndex == 0) {
                    val current = inputBuffer.getShort(position + 2 * channelIndex)
                    summedUp = (summedUp + current).toShort()
                }
            }
            fftBuffer.putShort(summedUp)
            position += inputAudioFormat.numberOfChannels * 2
        }

        // Reset input buffer to original position.
        inputBuffer.position(position)

        processFFT(this.fftBuffer)
    }

    private var filteredOpenY = 0f
    private var lastValidForm = 0f
    private fun processFFT(buffer: ByteBuffer) {
        if (noise == null) {
            return
        }
        srcBuffer.put(buffer.array())
        srcBufferPosition += buffer.array().size
        // Since this is PCM 16 bit, each sample will be 2 bytes.
        // So to get the sample size in the end, we need to take twice as many bytes off the buffer
        val bytesToProcess = SAMPLE_SIZE * 2
        while (srcBufferPosition > bytesToProcess) {
            // Move to start of
            srcBuffer.position(0)

            srcBuffer.asShortBuffer().get(tempShortArray, 0, SAMPLE_SIZE)
            tempShortArray.forEachIndexed { index, sample ->
                // Normalize to value between -1.0 and 1.0
                src[index] = sample.toFloat() / Short.MAX_VALUE
            }

            srcBuffer.position(bytesToProcess)
            srcBuffer.compact()
            srcBufferPosition -= bytesToProcess
            srcBuffer.position(srcBufferPosition)
            val dst = FloatArray(SAMPLE_SIZE + 2)
            val fft = noise?.fft(src, dst)!!

            this.fft = fft

            // 在 processFFT 中整合
            val params = getVowelParameters()
            val targetOpenY = params["openY"] ?: 0f
            val targetForm = params["form"] ?: 0f

            // 平滑 OpenY (快开慢合)
            smoothedOpenY += (targetOpenY - smoothedOpenY) * (if(targetOpenY > smoothedOpenY) 0.5f else 0.2f)
            // 平滑 Form (极慢平滑，防止嘴角抽动)
            // 爆发式开口：只要有声音，立刻弹开
            if (targetOpenY > smoothedOpenY) {
                smoothedOpenY += (targetOpenY - smoothedOpenY) * 0.9f // 提高到 0.8，几乎一帧到位
            } else {
                smoothedOpenY += (targetOpenY - smoothedOpenY) * 0.2f // 闭合保持平滑
            }

//            if (smoothedOpenY > 0.1f) {
//                smoothedForm += (targetForm - smoothedForm) * 0.15f
//            } else {
//                smoothedForm += (0f - smoothedForm) * 0.1f // 闭嘴时回归中性
//            }

            // 门限控制：只有开口度超过 15% 时，才认为当前的频率分析是可靠的元音
            if (targetOpenY > 0.15f) {
                // 增加 Form 的平滑因子 (从 0.15 提高到 0.3)，让口型切换更跟手
                // 否则你会觉得声音变了，但嘴角还没动
                smoothedForm += (targetForm - smoothedForm) * 0.3f
                lastValidForm = smoothedForm
            } else if (targetOpenY < 0.05f) {
                // 当嘴巴快闭合时，缓慢回归 0，而不是瞬间弹回
                smoothedForm += (0f - smoothedForm) * 0.1f
            } else {
                // 在中间地带（0.05 - 0.15），锁定状态，不更新也不回归
                // 这样可以避免由于能量不稳导致的嘴角抽动
                smoothedForm = lastValidForm
            }


            // --- 新增计算 ---
//            val targetOpenY = getCalculateOpenY()
//            val rawForm = getCalculateForm()
//
//            // 1. 第一层：非线性响应（处理爆发力）
//            val speed = if (targetOpenY > smoothedOpenY) 0.45f else 0.15f
//            smoothedOpenY += (targetOpenY - smoothedOpenY) * speed
//
//            // 2. 第二层：低通滤波（处理“跳动感”）
//            // 增加一个极小的平滑因子 (0.15f)，它会吸收高频的抖动，让动作看起来有“肉感”
//            filteredOpenY = filteredOpenY + 0.15f * (smoothedOpenY - filteredOpenY)
//
//            // 3. 强力闭合：如果目标已经趋近于0，快速切断，防止悬浮
//            if (targetOpenY < 0.05f && filteredOpenY < 0.1f) {
//                filteredOpenY = 0f
//                smoothedOpenY = 0f
//            }



//
//            if (targetOpenY > smoothedOpenY) {
//                // 调大开口系数 (原 0.45 -> 0.7)，让嘴巴在 1-2 帧内瞬间弹到最高点
//                smoothedOpenY += (targetOpenY - smoothedOpenY) * 0.9f
//            } else {
//                // 闭口保持中等速度 (0.2 - 0.3)，增加一点自然的肌肉感
//                smoothedOpenY += (targetOpenY - smoothedOpenY) * 0.25f
//            }
//            // 强制切断：如果目标是0，且当前已经很小，直接闭严
//            if (targetOpenY <= 0f && smoothedOpenY < 0.08f) {
//                smoothedOpenY = 0f
//            }


            // EMA 平滑
           // smoothedOpenY = smoothedOpenY + smoothingFactor * (rawOpenY - smoothedOpenY)
          //  smoothedForm = smoothedForm + smoothingFactor * (rawForm - smoothedForm)

            // 如果你在 Swai SDK 中需要导出这两个值：
            // this.latestOpenY = smoothedOpenY
            // this.latestForm = smoothedForm
        }
    }

    private fun durationUsToFrames(sampleRate: Int, durationUs: Long): Long {
        return durationUs * sampleRate / TimeUnit.MICROSECONDS.convert(1, TimeUnit.SECONDS)
    }

    private fun getPcmFrameSize(channelCount: Int): Int {
        // assumes PCM_16BIT
        return channelCount * 2
    }

    private fun getAudioTrackChannelConfig(channelCount: Int): Int {
        return when (channelCount) {
            1 -> android.media.AudioFormat.CHANNEL_OUT_MONO
            2 -> android.media.AudioFormat.CHANNEL_OUT_STEREO
            // ignore other channel counts that aren't used in LiveKit
            else -> android.media.AudioFormat.CHANNEL_INVALID
        }
    }

    private fun getDefaultBufferSizeInBytes(AudioFormat2: AudioFormat2): Int {
        val outputPcmFrameSize = getPcmFrameSize(AudioFormat2.numberOfChannels)
        val minBufferSize =
            AudioTrack.getMinBufferSize(
                AudioFormat2.sampleRate,
                getAudioTrackChannelConfig(AudioFormat2.numberOfChannels),
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )

        check(minBufferSize != AudioTrack.ERROR_BAD_VALUE)
        val multipliedBufferSize = minBufferSize * 4
        val minAppBufferSize =
            durationUsToFrames(AudioFormat2.sampleRate, 30 * 1000).toInt() * outputPcmFrameSize
        val maxAppBufferSize = max(
            minBufferSize.toLong(),
            durationUsToFrames(AudioFormat2.sampleRate, 500 * 1000) * outputPcmFrameSize
        ).toInt()
        val bufferSizeInFrames =
            multipliedBufferSize.coerceIn(minAppBufferSize, maxAppBufferSize) / outputPcmFrameSize
        return bufferSizeInFrames * outputPcmFrameSize
    }

    // 在 FFTAudioAnalyzer 类中微调变量
    private var maxMagnitude = 0.01f // 初始值调小一半
    private val decayRate = 0.008f   // 衰减加快 (原 0.005)，让基准更灵敏


    // 在类成员变量中增加
    private var lastRawOpenY = 0f

    fun getCalculateOpenY(): Float {
        val currentFft = fft ?: return 0f
        var energy = 0f

        // 限制频段，避开 50/60Hz 的交流电啸叫和高频白噪
        val startBin = 3   // ~280Hz
        val endBin = 28    // ~2.6kHz

        for (i in (startBin * 2) until (endBin * 2).coerceAtMost(currentFft.size) step 2) {
            val real = currentFft[i]
            val imag = currentFft[i + 1]
            energy += (real * real + imag * imag)
        }

        val magnitude = sqrt(energy / (endBin - startBin))

        // 动态基准跟踪
        if (magnitude > maxMagnitude) {
            maxMagnitude = magnitude
        } else {
            maxMagnitude = max(0.005f, maxMagnitude - 0.008f)
        }

        // --- 修复抖动的核心：动态死区 (Deadzone) ---
        val gate = maxMagnitude * 0.15f

        // 如果能量太低，直接归零，切断底噪抖动
        if (magnitude < gate || magnitude < 0.002f) return 0f

        val normalized = ((magnitude - gate) / (maxMagnitude - gate) * 2.2f).coerceIn(0f, 1.0f)
        val currentRaw = normalized.pow(1.5f)

        // --- 修复抖动核心 2：滞后滤波 ---
        // 如果当前计算出的值与上一帧变化非常小（例如小于 0.05），则认为是在抖动，保持上一帧的值
        val finalOpenY = if (abs(currentRaw - lastRawOpenY) < 0.08f) {
            lastRawOpenY
        } else {
            currentRaw
        }

        lastRawOpenY = finalOpenY
        return finalOpenY.coerceIn(0f, 1.0f)
    }




    fun getCalculateForm(): Float {
        val currentFft = fft ?: return 0f
        var weightedSum = 0f
        var totalMag = 0f

        // 同样限定在人声频段
        val numBins = (currentFft.size / 2).coerceAtMost(128)
        for (i in 0 until numBins) {
            val real = currentFft[i * 2]
            val imag = currentFft[i * 2 + 1]
            val magnitude = sqrt(real * real + imag * imag)

            weightedSum += i * magnitude
            totalMag += magnitude
        }

        // 如果声音太小，Form 应该回归中性（0），而不是乱跳
        if (totalMag < 0.05f) return 0f

        val centroid = weightedSum / (totalMag * numBins)

        // 映射逻辑：0.18 附近为中性
        val form = (centroid - 0.18f) / 0.15f
        return form.coerceIn(-1.0f, 1.0f)
    }
}

data class AudioFormat2(val bitsPerSample: Int, val sampleRate: Int, val numberOfChannels: Int)