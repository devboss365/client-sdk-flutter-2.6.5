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
import org.webrtc.AudioTrackSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CustomVisualizer(
    binaryMessenger: BinaryMessenger,
) : EventChannel.StreamHandler, AudioTrackSink {
    private var eventChannel: EventChannel? = null
    private var eventSink: EventChannel.EventSink? = null
    var lipSyncEstimatorStream = LipSyncEstimatorStream()

    private var voice = FloatArray(2)

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
        voice[0] = openY;
        voice[1] = mouthForm;
        handler.post {
            eventSink?.success(voice)
        }
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
        eventChannel = EventChannel(binaryMessenger, "io.livekit.audio.visualizer/eventChannel-remote-voice")
        eventChannel?.setStreamHandler(this)
    }
}




