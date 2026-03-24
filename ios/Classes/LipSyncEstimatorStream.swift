import Foundation

/**
 * 音频口型估算模块
 *
 * 从音频帧估算 ParamMouthOpenY 和 ParamMouthForm：
 * - ParamMouthOpenY: [0, 1] 张嘴幅度，基于 RMS + 平滑
 * - ParamMouthForm: [-1, 1] 嘴型变形，基于 Disney viseme 频谱匹配
 */
class LipSyncEstimatorStream {
    private let sampleRate: Int
    private let fftSize: Int

    private let hann: [Float]
    private let freqs: [Float]
    private let hfMask: [Bool]
    private let lfMask: [Bool]

    private var smoothedForm: Float = 0.0
    private var smoothedOpen: Float = 0.0
    private var outputForm: Float = 0.0
    private var formHoldCounter: Int = 0

    // 对应 Kotlin 的 companion object 常量
    struct Constants {
        static let VISEME_FEATURES: [Int: (features: [Float], form: Float)] = [
            1:  ([0.16, 0.13, 0.71], 0.0),   // p/b/m 闭嘴，中性
            2:  ([0.05, 0.07, 0.93], -0.6),  // w 圆唇，生气
            3:  ([0.12, 0.13, 0.64], -0.3),  // r 稍圆，略生气
            4:  ([0.33, 0.50, 0.36], 0.0),   // f/v 中性
            5:  ([0.43, 0.40, 0.26], 0.3),   // th 微笑
            6:  ([0.29, 0.23, 0.43], 0.0),   // l 中性
            7:  ([0.48, 0.60, 0.26], 0.3),   // d/t/z/s/n 微笑
            8:  ([0.62, 0.83, 0.17], 0.3),   // ʃ/tʃ/dʒ/ʒ 微笑
            9:  ([0.24, 0.27, 0.50], 0.0),   // j/g/k 中性
            10: ([0.90, 0.20, 0.11], 1.0),   // i/ɪ/e 微笑（扁嘴最大）
            11: ([0.10, 0.07, 0.86], -0.6),  // o/u 圆唇，生气
            12: ([0.33, 0.20, 0.50], 0.5)    // a 大开口，微笑
        ]

        static let WEIGHTS: [Float] = [0.5, 0.20, 0.30]
        static let CENTROID_MIN: Double = 400.0
        static let CENTROID_MAX: Double = 2500.0
        static let HF_FREQ: Double = 4000.0
        static let LF_FREQ: Double = 500.0
        static let HF_MAX: Double = 0.30
        static let LF_MAX: Double = 0.70

        // form 平滑参数
        static let FORM_ALPHA: Float = 0.7
        static let FORM_MIN_DELTA: Float = 0.03
        static let FORM_QUANTIZE_LEVELS: [Float] = [-1.0, -0.6, -0.3, 0.0, 0.3, 0.5, 1.0]
        static let FORM_HOLD_FRAMES: Int = 5

        // open_y 平滑参数
        static let OPEN_ALPHA_UP: Float = 0.3
        static let OPEN_ALPHA_DOWN: Float = 0.25
        static let OPEN_MIN_DELTA: Float = 0.01
    }

    init(sampleRate: Int = 24000, fftSize: Int = 2048) {
        self.sampleRate = sampleRate
        self.fftSize = fftSize

        // 初始化 Hann 窗
        self.hann = (0..<fftSize).map { i in
            let n = Float(fftSize - 1)
            return Float(0.5 * (1.0 - cos(2.0 * .pi * Double(i) / Double(n))))
        }

        // 初始化频率轴
        let halfSize = fftSize / 2 + 1
        self.freqs = (0..<halfSize).map { i in
            Float(Double(i) * Double(sampleRate) / Double(fftSize))
        }

        // 掩码
        self.hfMask = freqs.map { Double($0) >= Constants.HF_FREQ }
        self.lfMask = freqs.map { Double($0) <= Constants.LF_FREQ }
    }

    /**
     * 从单声道 PCM 片段估算口型参数。
     */
    func estimate(pcmMono: [Float]) -> (openY: Float, mouthForm: Float) {
        let rms = computeRms(pcmMono: pcmMono)
        let openY = estimateOpen(rms: rms)
        let mouthForm = estimateForm(pcmMono: pcmMono, rms: rms)
        return (openY, mouthForm)
    }

    private func estimateOpen(rms: Float) -> Float {
        // 1. 提高倍率至 6.0，降低指数至 0.7
        let rawOpen = pow(max(min(rms * 6.0, 1.0), 0.0), 0.7)

        let alpha: Float
        if abs(rawOpen - smoothedOpen) < Constants.OPEN_MIN_DELTA {
            alpha = 0.0
        } else {
            // 2. 这里的速度可以根据上面的 Constants 调整
            alpha = rawOpen > smoothedOpen ? 0.6 : 0.2
        }

        if alpha > 0.0 {
            smoothedOpen = smoothedOpen * (1.0 - alpha) + rawOpen * alpha
        }

        return round(smoothedOpen * 10000) / 10000.0
    }

    private func computeRms(pcmMono: [Float]) -> Float {
        var sum: Double = 0.0
        for s in pcmMono {
            sum += Double(s) * Double(s)
        }
        return Float(sqrt(sum / Double(pcmMono.count)))
    }

    private func estimateOpen2(rms: Float) -> Float {
        let rawOpen = pow(max(min(rms * 3.0, 1.0), 0.0), 0.8)

        let alpha: Float
        if abs(rawOpen - smoothedOpen) < Constants.OPEN_MIN_DELTA {
            alpha = 0.0
        } else {
            alpha = rawOpen > smoothedOpen ? Constants.OPEN_ALPHA_UP : Constants.OPEN_ALPHA_DOWN
        }

        if alpha > 0.0 {
            smoothedOpen = smoothedOpen * (1.0 - alpha) + rawOpen * alpha
        }

        return round(smoothedOpen * 10000) / 10000.0
    }

    private func estimateForm(pcmMono: [Float], rms: Float) -> Float {
        let targetForm: Float

        if rms < 0.02 {
            targetForm = 0.0
        } else {
            let features = extractFeatures(pcmMono: pcmMono)
            let result = findClosestVisemes(features: features)
            var raw = result.f1 * result.w1 + result.f2 * (1.0 - result.w1)
            raw = max(min(raw, 1.0), -1.0)
            targetForm = snapToLevel(value: raw)
        }

        var smoothed = Constants.FORM_ALPHA * smoothedForm + (1.0 - Constants.FORM_ALPHA) * targetForm

        if abs(smoothed - outputForm) < Constants.FORM_MIN_DELTA {
            smoothed = outputForm
        }

        if formHoldCounter > 0 {
            formHoldCounter -= 1
            smoothed = outputForm
        } else if outputForm != 0.0 && signChanged(oldVal: outputForm, newVal: smoothed) {
            formHoldCounter = Constants.FORM_HOLD_FRAMES
            smoothed = outputForm
        }

        smoothedForm = smoothed
        outputForm = smoothed

        return round(outputForm * 10000) / 10000.0
    }

    private func signChanged(oldVal: Float, newVal: Float) -> Bool {
        if abs(oldVal) < 0.1 || abs(newVal) < 0.1 { return false }
        return (oldVal > 0) != (newVal > 0)
    }

    private func snapToLevel(value: Float) -> Float {
        return Constants.FORM_QUANTIZE_LEVELS.min(by: { abs($0 - value) < abs($1 - value) }) ?? 0.0
    }

    private func extractFeatures(pcmMono: [Float]) -> [Float] {
        var samples = pcmMono
        if samples.count < fftSize {
            samples = [Float](repeating: 0.0, count: fftSize)
            for i in 0..<pcmMono.count {
                samples[i] = pcmMono[i]
            }
        }

        let windowed = (0..<fftSize).map { samples[$0] * hann[$0] }
        let spectrum = rfft(input: windowed)
        let power = spectrum.map { $0 * $0 }

        let totalPower = power.reduce(0.0) { $0 + Double($1) }
        if totalPower < 1e-10 { return [0.0, 0.0, 0.0] }

        var centroid: Double = 0.0
        for i in 0..<freqs.count {
            centroid += Double(freqs[i]) * Double(power[i])
        }
        centroid /= totalPower

        let centroidNorm = Float((centroid - Constants.CENTROID_MIN) / (Constants.CENTROID_MAX - Constants.CENTROID_MIN))
            .clamped(to: 0.0...1.0)

        var hfPower: Double = 0.0
        var lfPower: Double = 0.0
        for i in 0..<power.count {
            if hfMask[i] { hfPower += Double(power[i]) }
            if lfMask[i] { lfPower += Double(power[i]) }
        }

        let hfNorm = Float(hfPower / totalPower / Constants.HF_MAX).clamped(to: 0.0...1.0)
        let lfNorm = Float(lfPower / totalPower / Constants.LF_MAX).clamped(to: 0.0...1.0)

        return [centroidNorm, hfNorm, lfNorm]
    }

    private struct VisemeDistance {
        let dist: Float
        let form: Float
    }

    private func findClosestVisemes(features: [Float]) -> (f1: Float, f2: Float, w1: Float) {
        var dists = [VisemeDistance]()

        for (_, pair) in Constants.VISEME_FEATURES {
            var dist: Double = 0.0
            for i in 0..<Constants.WEIGHTS.count {
                let diff = Double(features[i]) - Double(pair.features[i])
                dist += Double(Constants.WEIGHTS[i]) * diff * diff
            }
            dists.append(VisemeDistance(dist: Float(sqrt(dist)), form: pair.form))
        }

        dists.sort { $0.dist < $1.dist }

        let d1 = dists[0]
        let d2 = dists[1]
        let total = d1.dist + d2.dist

        if total < 1e-8 { return (d1.form, d2.form, 0.5) }

        let w1 = 1.0 - d1.dist / total
        return (d1.form, d2.form, w1)
    }

    func reset() {
        smoothedForm = 0.0
        smoothedOpen = 0.0
        outputForm = 0.0
        formHoldCounter = 0
    }
}

// MARK: - Helpers

extension Comparable {
    func clamped(to limits: ClosedRange<Self>) -> Self {
        return min(max(self, limits.lowerBound), limits.upperBound)
    }
}

/** 简易 FFT 实现 (rfft) */
private func rfft(input: [Float]) -> [Float] {
    let n = input.count
    guard n > 0 else { return [] }

    var real = input.map { Double($0) }
    var imag = [Double](repeating: 0.0, count: n)

    fft(real: &real, imag: &imag)

    let halfN = n / 2 + 1
    var result = [Float](repeating: 0.0, count: halfN)
    for i in 0..<halfN {
        result[i] = Float(sqrt(real[i] * real[i] + imag[i] * imag[i]))
    }
    return result
}

/** Cooley-Tukey 基2 FFT */
private func fft(real: inout [Double], imag: inout [Double]) {
    let n = real.count
    var j = 0
    for i in 1..<n {
        var bit = n >> 1
        while (j & bit) != 0 {
            j ^= bit
            bit >>= 1
        }
        j ^= bit
        if i < j {
            real.swapAt(i, j)
            imag.swapAt(i, j)
        }
    }

    var len = 2
    while len <= n {
        let halfLen = len / 2
        let angle = -2.0 * .pi / Double(len)
        let wReal = cos(angle)
        let wImag = sin(angle)

        var i = 0
        while i < n {
            var curReal = 1.0
            var curImag = 0.0
            for k in 0..<halfLen {
                let uReal = real[i + k]
                let uImag = imag[i + k]
                let vReal = real[i + k + halfLen] * curReal - imag[i + k + halfLen] * curImag
                let vImag = real[i + k + halfLen] * curImag + imag[i + k + halfLen] * curReal
                real[i + k] = uReal + vReal
                imag[i + k] = uImag + vImag
                real[i + k + halfLen] = uReal - vReal
                imag[i + k + halfLen] = uImag - vImag

                let nextCurReal = curReal * wReal - curImag * wImag
                curImag = curReal * wImag + curImag * wReal
                curReal = nextCurReal
            }
            i += len
        }
        len *= 2
    }
}