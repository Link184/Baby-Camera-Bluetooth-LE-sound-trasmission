import AVFoundation

/// Plays the baby unit's sound, relayed by the iPhone app, on the watch's speaker (or its Bluetooth
/// headphones). The iPhone sends it in bursts 4 times a second, so after a start or a gap playback
/// waits until `prefill` seconds are queued. Main thread only.
final class SoundPlayer {
    private static let sampleRate = 8_000.0
    private static let prefill = 0.5
    private static let maxQueued = 1.5

    private let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate, channels: 1, interleaved: false)!
    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?

    /// Seconds handed to the player and not played yet.
    private var queued = 0.0

    /// Held back until there's enough to start without gaps.
    private var waiting: [AVAudioPCMBuffer] = []

    /// Counts starts, so buffers finishing after a stop don't count against the next start.
    private var generation = 0

    func start() {
        guard engine == nil else { return }
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            try AVAudioSession.sharedInstance().setActive(true)
            let engine = AVAudioEngine()
            let player = AVAudioPlayerNode()
            engine.attach(player)
            engine.connect(player, to: engine.mainMixerNode, format: format)
            try engine.start()
            player.play()
            self.engine = engine
            self.player = player
        } catch {
            print("Couldn't start the sound: \(error)")
        }
    }

    func stop() {
        player?.stop()
        engine?.stop()
        engine = nil
        player = nil
        queued = 0
        waiting = []
        generation += 1
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// One sound packet as the baby unit sent it; dropped if too much is already queued.
    func play(_ packet: Data) {
        guard let player, let buffer = decode(packet) else { return }
        let seconds = Double(buffer.frameLength) / Self.sampleRate
        if queued + seconds > Self.maxQueued { return }
        waiting.append(buffer)
        let waitingSeconds = waiting.reduce(0) { $0 + Double($1.frameLength) } / Self.sampleRate
        // Playing on: queue right away. After a gap: wait for a little reserve first.
        guard queued > 0 || waitingSeconds >= Self.prefill else { return }
        let generation = generation
        for buffer in waiting {
            let seconds = Double(buffer.frameLength) / Self.sampleRate
            queued += seconds
            player.scheduleBuffer(buffer) { [weak self] in
                DispatchQueue.main.async {
                    guard let self, self.generation == generation else { return }
                    self.queued = max(0, self.queued - seconds)
                }
            }
        }
        waiting = []
    }

    private func decode(_ packet: Data) -> AVAudioPCMBuffer? {
        guard let samples = AdpcmPacket.decode(packet),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)),
              let channel = buffer.floatChannelData?[0] else { return nil }
        for (i, sample) in samples.enumerated() {
            channel[i] = Float(sample) / 32768
        }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        return buffer
    }
}

/// Decodes the baby unit's sound packets: the shared code's AudioPacket and IMA ADPCM. KEEP IN SYNC
/// with shared/.../audio/AudioCodec.kt. Format: 4-byte sequence number, 1-byte level, the ADPCM state
/// (2-byte predictor, 1-byte step index), then 4-bit samples, low nibble first.
enum AdpcmPacket {
    private static let header = 8

    static func decode(_ packet: Data) -> [Int16]? {
        let bytes = [UInt8](packet)
        guard bytes.count >= header else { return nil }
        var predictor = Int(Int16(bitPattern: UInt16(bytes[5]) << 8 | UInt16(bytes[6])))
        var index = min(Int(bytes[7]), steps.count - 1)
        var pcm = [Int16]()
        pcm.reserveCapacity((bytes.count - header) * 2)
        for byte in bytes[header...] {
            for nibble in [Int(byte & 0x0F), Int(byte >> 4)] {
                let step = steps[index]
                var delta = step >> 3
                if nibble & 4 != 0 { delta += step }
                if nibble & 2 != 0 { delta += step >> 1 }
                if nibble & 1 != 0 { delta += step >> 2 }
                predictor = min(32767, max(-32768, nibble & 8 != 0 ? predictor - delta : predictor + delta))
                index = min(steps.count - 1, max(0, index + indexChanges[nibble]))
                pcm.append(Int16(predictor))
            }
        }
        return pcm
    }

    /// Splits the "sound" of a message from the iPhone (the shared code's `encodeSoundBatch`):
    /// each packet is a 2-byte big-endian length, then the packet.
    static func split(_ batch: Data) -> [Data] {
        let bytes = [UInt8](batch)
        var packets: [Data] = []
        var at = 0
        while at + 2 <= bytes.count {
            let size = Int(bytes[at]) << 8 | Int(bytes[at + 1])
            guard at + 2 + size <= bytes.count else { break }
            packets.append(Data(bytes[(at + 2)..<(at + 2 + size)]))
            at += 2 + size
        }
        return packets
    }

    private static let indexChanges = [-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8]
    private static let steps = [
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66,
        73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408,
        449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630,
        9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794,
        32767,
    ]
}
