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
 */

package io.livekit.plugin

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class Visualizer(
    private var barCount: Int,
    private var isCentered: Boolean,
    private var smoothTransition: Boolean,
    audioTrack: LKAudioTrack,
    binaryMessenger: BinaryMessenger,
    visualizerId: String
) : EventChannel.StreamHandler, AudioTrackSink {
    private var eventChannel: EventChannel? = null
    private var eventSink: EventChannel.EventSink? = null
//    private var ffiAudioAnalyzer = FFTAudioAnalyzer()
    private var audioTrack: LKAudioTrack? = audioTrack
    private var amplitudes: FloatArray = FloatArray(0)
    private var bands: FloatArray
    private var yes = FloatArray(2)
    private var no = FloatArray(2)
    private var loPass: Int = 0
    private var hiPass: Int = 80

    private var droppedFrameCount = 0L


    private var audioFormat = AudioFormat(16, 48000, 1)

    var lipSyncEstimatorStream = LipSyncEstimatorStream();

    fun stop() {
        audioTrack?.removeSink(this)
        eventChannel?.setStreamHandler(null)
//        ffiAudioAnalyzer.release()

    }

    fun FloatArray.isAllEqual(tolerance: Float = 0.0001f): Boolean {
        if (this.isEmpty()) return true

        val first = this[0]

        // 如果有容差，遍历比较；如果没有容差，直接用 distinct
        if (tolerance > 0f) {
            return this.all { value ->
                kotlin.math.abs(value - first) <= tolerance
            }
        } else {
            // 严格相等 (不推荐用于 FFT 结果，除非是整数化后的)
            return this.distinct().size == 1
        }
    }


    private fun convertToMono(stereoData: FloatArray, channels: Int): FloatArray {
        val monoSize = stereoData.size / channels
        val monoData = FloatArray(monoSize)
        for (i in 0 until monoSize) {
            // 简单取左声道，或者取所有声道的平均值
            var sum = 0f
            for (c in 0 until channels) {
                sum += stereoData[i * channels + c]
            }
            monoData[i] = sum / channels
        }
        return monoData
    }

    override fun onData(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
        absoluteCaptureTimestampMs: Long
    ) {

        // 1. 确保 ByteOrder 是小端序（WebRTC 标准）
        audioData.order(ByteOrder.LITTLE_ENDIAN)

        // 2. 创建一个 ShortBuffer 来读取 16-bit PCM
        val shortBuffer = audioData.asShortBuffer()
        val totalSamples = shortBuffer.remaining()

        // 3. 初始化 FloatArray
        val floatArray = FloatArray(totalSamples)

        // 4. 转换并归一化 (将 -32768..32767 映射到 -1.0..1.0)
        for (i in 0 until totalSamples) {
            floatArray[i] = shortBuffer.get(i) / 32768.0f
        }

        // 5. 如果是多声道且你的 estimate 函数只需要单声道 (pcmMono)
        val monoData = if (numberOfChannels > 1) {
            convertToMono(floatArray, numberOfChannels)
        } else {
            floatArray
        }
        val (openY, mouthForm) = lipSyncEstimatorStream.estimate(monoData);
        yes.set(0, openY);
        yes.set(1, mouthForm);
        handler.post {
            eventSink?.success(yes)
        }
    }

    /**
     * Extracts int16 PCM bytes from an int16 source buffer.
     *
     * Fast path when channel counts match (direct copy).
     * Otherwise keeps only the first [outChannels] channels, interleaved.
     */
    private fun extractAsInt16Bytes(
        buffer: ByteBuffer,
        srcChannels: Int,
        outChannels: Int,
        numberOfFrames: Int
    ): ByteArray {
        // Fast path: matching channel count — bulk copy.
        if (srcChannels == outChannels) {
            val totalBytes = numberOfFrames * outChannels * 2
            val out = ByteArray(totalBytes)
            buffer.get(out, 0, totalBytes.coerceAtMost(buffer.remaining()))
            return out
        }

        // Channel reduction: keep first outChannels.
        val out = ByteArray(numberOfFrames * outChannels * 2)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        for (frame in 0 until numberOfFrames) {
            val srcOffset = frame * srcChannels * 2
            for (ch in 0 until outChannels) {
                val byteIndex = srcOffset + ch * 2
                if (byteIndex + 1 < buffer.capacity()) {
                    buffer.position(byteIndex)
                    outBuf.putShort((frame * outChannels + ch) * 2, buffer.short)
                }
            }
        }

        return out
    }

    /**
     * Converts int16 PCM source to float32 bytes.
     *
     * Each int16 sample is scaled to the [-1.0, 1.0] range.
     * Only the first [outChannels] channels are kept.
     */
    private fun extractAsFloat32Bytes(
        buffer: ByteBuffer,
        srcChannels: Int,
        outChannels: Int,
        numberOfFrames: Int
    ): ByteArray {
        val out = ByteArray(numberOfFrames * outChannels * 4)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        for (frame in 0 until numberOfFrames) {
            val srcOffset = frame * srcChannels * 2
            for (ch in 0 until outChannels) {
                val byteIndex = srcOffset + ch * 2
                if (byteIndex + 1 < buffer.capacity()) {
                    buffer.position(byteIndex)
                    val sampleFloat = buffer.short.toFloat() / Short.MAX_VALUE
                    outBuf.putFloat((frame * outChannels + ch) * 4, sampleFloat)
                }
            }
        }

        return out
    }


    private val handler: Handler by lazy {
        Handler(Looper.getMainLooper())
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    init {
        eventChannel = EventChannel(binaryMessenger, "io.livekit.audio.visualizer/eventChannel-" + audioTrack.id() + "-" + visualizerId)
        eventChannel?.setStreamHandler(this)
        bands = FloatArray(barCount)
//        ffiAudioAnalyzer.configure(audioFormat)
        audioTrack.addSink(this)
    }
}

private fun centerBands(bands: FloatArray): FloatArray {
    val centeredBands = FloatArray(bands.size)
    var leftIndex = bands.size / 2;
    var rightIndex = leftIndex;

    for (i in bands.indices) {
        val value = bands[i]
        if (i % 2 == 0) {
            // Place value to the right
            centeredBands[rightIndex] = value
            rightIndex += 1
        } else {
            // Place value to the left
            leftIndex -= 1
            centeredBands[leftIndex] = value
        }
    }
    return centeredBands
}

private  fun smoothTransition(from: Float, to: Float, factor: Float): Float {
    val delta = to - from
    val easedFactor = easeInOutCubic(factor)
    return from + delta * easedFactor
}

private fun easeInOutCubic(t: Float): Float {
    return if (t < 0.5) {
        4 * t * t * t
    } else {
        1 - (2 * t - 2).pow(3) / 2
    }
}

private const val MIN_CONST = 0.1f
private const val MAX_CONST = 8.0f

private fun calculateAmplitudeBarsFromFFT(
    fft: List<Float>,
    averages: FloatArray,
    barCount: Int,
): FloatArray {
    val amplitudes = FloatArray(barCount)
    if (fft.isEmpty()) {
        return amplitudes
    }

    // We average out the values over 3 occurences (plus the current one), so big jumps are smoothed out
    // Iterate over the entire FFT result array.
    for (barIndex in 0 until barCount) {
        // Note: each FFT is a real and imaginary pair.
        // Scale down by 2 and scale back up to ensure we get an even number.
        val prevLimit = (round(fft.size.toFloat() / 2 * barIndex / barCount).toInt() * 2)
            .coerceIn(0, fft.size - 1)
        val nextLimit = (round(fft.size.toFloat() / 2 * (barIndex + 1) / barCount).toInt() * 2)
            .coerceIn(0, fft.size - 1)

        var accum = 0f
        // Here we iterate within this single band
        for (i in prevLimit until nextLimit step 2) {
            // Convert real and imaginary part to get energy

            val realSq = fft[i]
                .toDouble()
                .pow(2.0)
            val imaginarySq = fft[i + 1]
                .toDouble()
                .pow(2.0)
            val raw = sqrt(realSq + imaginarySq).toFloat()

            accum += raw
        }

        // A window might be empty which would result in a 0 division
        if ((nextLimit - prevLimit) != 0) {
            accum /= (nextLimit - prevLimit)
        } else {
            accum = 0.0f
        }

        val smoothingFactor = 1f
        var avg = averages[barIndex]
        avg += (accum - avg / smoothingFactor)
        averages[barIndex] = avg

        var amplitude = avg.coerceIn(MIN_CONST, MAX_CONST)
        amplitude -= MIN_CONST
        amplitude /= (MAX_CONST - MIN_CONST)
        amplitudes[barIndex] = amplitude
    }

    return amplitudes
}

