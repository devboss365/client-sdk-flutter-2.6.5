/*
 * Copyright 2025 LiveKit
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

import AVFoundation
import WebRTC
import Foundation

#if os(macOS)
import Cocoa
import FlutterMacOS
#else
import Flutter
import UIKit
#endif


extension Array where Element == Float {

    func isAllEqual(tolerance: Float = 0.0001) -> Bool {
             // 1. 获取第一个元素，若数组为空则直接返回 true
            guard let firstElement = self.first else {
                 return true
            }

            // 2. 容差大于 0 时，执行近似相等逻辑
            if tolerance > 0 {
            // 使用 abs() 处理浮点数差异，allSatisfy 具有短路特性
                 return self.allSatisfy { abs($0 - firstElement) <= tolerance }
            }

        // 3. 严格相等 (tolerance <= 0)
        // 建议不使用 Set(self)，因为 Set 会进行全量哈希计算，性能较低
        // 直接使用 allSatisfy 比较原始值更高效
            return self.allSatisfy { $0 == firstElement }
    }
}





public class Visualizer: NSObject, RTCAudioRenderer, FlutterStreamHandler {
    private var eventSink: FlutterEventSink?

    private var channel: FlutterEventChannel?

    public func onListen(withArguments _: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        eventSink = events
        return nil
    }

    public func onCancel(withArguments _: Any?) -> FlutterError? {
        eventSink = nil
        return nil
    }

    public let isCentered: Bool
    public let smoothingFactor: Float
    public let smoothTransition: Bool
    public var bands: [Float]

    private let _processor: AudioVisualizeProcessor
    private weak var _track: AudioTrack?




    public init(track: AudioTrack?,
                binaryMessenger: FlutterBinaryMessenger,
                bandCount: Int = 7,
                isCentered: Bool = true,
                smoothTransition: Bool = true,
                smoothingFactor: Float = 0.3,
                visualizerId: String)
    {
        self.isCentered = isCentered
        self.smoothingFactor = smoothingFactor
        self.smoothTransition = smoothTransition
        bands = Array(repeating: 0.0, count: bandCount)
        _processor = AudioVisualizeProcessor(bandsCount: bandCount)
        _track = track
        super.init()
        _track?.add(audioRenderer: self)
        let channelName = "io.livekit.audio.visualizer/eventChannel-" + (track?.mediaTrack.trackId ?? "") + "-" + visualizerId
        channel = FlutterEventChannel(name: channelName, binaryMessenger: binaryMessenger)
        channel?.setStreamHandler(self)
    }

    public func stop() {
        _track?.remove(audioRenderer: self)
        channel?.setStreamHandler(nil)
    }

    deinit {
        stop()
    }

    public func render(pcmBuffer: AVAudioPCMBuffer) {
        let newBands = _processor.process(pcmBuffer: pcmBuffer)
        guard var newBands else { return }

        // If centering is enabled, rearrange the normalized bands
        if isCentered {
            newBands.sort(by: >)
            newBands = centerBands(newBands)
        }

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }

            bands = zip(bands, newBands).map { old, new in
                if self.smoothTransition {
                    return self._smoothTransition(from: old, to: new, factor: self.smoothingFactor)
                }
                return new
            }
                
            if(bands.isAllEqual()){
                eventSink?([1.0])
            }else{
                eventSink?([2.0])
            }
            
           //eventSink?(bands)
        }
    }

    // MARK: - Private

    /// Centers the sorted bands by placing higher values in the middle.
    @inline(__always) private func centerBands(_ sortedBands: [Float]) -> [Float] {
        var centeredBands = [Float](repeating: 0, count: sortedBands.count)
        var leftIndex = sortedBands.count / 2
        var rightIndex = leftIndex

        for (index, value) in sortedBands.enumerated() {
            if index % 2 == 0 {
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

    /// Applies an easing function to smooth the transition.
    @inline(__always) private func _smoothTransition(from oldValue: Float, to newValue: Float, factor: Float) -> Float {
        // Calculate the delta change between the old and new value
        let delta = newValue - oldValue
        // Apply an ease-in-out cubic easing curve
        let easedFactor = _easeInOutCubic(t: factor)
        // Calculate and return the smoothed value
        return oldValue + delta * easedFactor
    }

    /// Easing function: ease-in-out cubic
    @inline(__always) private func _easeInOutCubic(t: Float) -> Float {
        t < 0.5 ? 4 * t * t * t : 1 - pow(-2 * t + 2, 3) / 2
    }
}







