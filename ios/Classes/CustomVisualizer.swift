//
//  CustomVisualizer.swift
//  Pods
//
//  Created by dev on 3/27/26.
//

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

@objc(CustomVisualizer) // 确保 OC 里类名一致
class CustomVisualizer: NSObject, FlutterStreamHandler{
    
    private var eventChannel: FlutterEventChannel?
    private var eventSink: FlutterEventSink?
    
    // 假设你已经有一个 Swift 版的 LipSyncEstimatorStream
    private let _lipSyncEstimator = LipSyncEstimatorStream(sampleRate: 48000, fftSize: 2048)

    
    init(binaryMessenger: FlutterBinaryMessenger) {
        super.init()
        eventChannel = FlutterEventChannel(name: "io.livekit.audio.visualizer/eventChannel-remote-voice", binaryMessenger: binaryMessenger)
        eventChannel?.setStreamHandler(self)
        
    }
    
    // MARK: - FlutterStreamHandler
    
    func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        self.eventSink = events
        return nil
    }
    
    func onCancel(withArguments arguments: Any?) -> FlutterError? {
        self.eventSink = nil
        return nil
    }
    
    @objc(render:)
    public func render(pcmBuffer: AVAudioPCMBuffer) {
            let frameCount = Int(pcmBuffer.frameLength)
            guard frameCount > 0 else { return }

              var pcmArray: [Float] = []

                // 1. 尝试读取 Float32 数据 (最理想情况)
               if let floatData = pcmBuffer.floatChannelData?[0] {
                        pcmArray = Array(UnsafeBufferPointer(start: floatData, count: frameCount))
                }
                // 2. 尝试读取 Int16 数据并手动归一化 (WebRTC 常见情况)
                else if let int16Data = pcmBuffer.int16ChannelData?[0] {
                        let int16Buffer = UnsafeBufferPointer(start: int16Data, count: frameCount)
                        // 将 Int16 (-32768 ~ 32767) 转换为 Float (-1.0 ~ 1.0)
                        pcmArray = int16Buffer.map { Float($0) / 32768.0 }
                }

                // 3. 只有拿到数据才进行算法处理
                if !pcmArray.isEmpty {
                let lipSyncResult = _lipSyncEstimator.estimate(pcmMono: pcmArray)

                DispatchQueue.main.async { [weak self] in
                    guard let self = self else { return }
                    // 发送给 Flutter
                    self.eventSink?([lipSyncResult.openY, lipSyncResult.mouthForm])
                }
            } else {
                // 如果走到这里，说明 pcmBuffer 既不是 Float 也不含有效 Int16
                print("Buffer format Error: \(pcmBuffer.format.description) 😅")
            }
        }

    
}
