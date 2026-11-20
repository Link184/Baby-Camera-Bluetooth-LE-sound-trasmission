import SwiftUI

/// The sound meter, or why there's none.
struct WatchView: View {
    @StateObject private var phone = PhoneLink()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        // Redraws every second, so "nothing from the iPhone for a while" shows without a new message.
        TimelineView(.periodic(from: .now, by: 1)) { context in
            content(now: context.date)
        }
        .onAppear { phone.resume() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { phone.resume() } else { phone.pause() }
        }
    }

    @ViewBuilder
    private func content(now: Date) -> some View {
        // The level comes 4 times a second; 3 s without it means the iPhone app isn't sending.
        let heardRecently = phone.lastHeard.map { now.timeIntervalSince($0) < 3 } ?? false
        if phone.phoneReachable == false {
            MessageView(title: "iPhone out of reach", detail: "Keep your iPhone closer to the watch.")
        } else if !heardRecently {
            MessageView(title: "Waiting for the iPhone", detail: "Open BabyCamera on your iPhone.")
        } else if let state = phone.state, state.listening {
            MeterView(state: state, level: phone.level, soundOn: phone.soundOn,
                      onToggleSound: phone.toggleSound, onDismiss: phone.dismiss)
        } else {
            MessageView(title: phone.state?.status ?? "Not listening", detail: "Start the parent unit on your iPhone.")
        }
    }
}

private struct MeterView: View {
    let state: PhoneLink.State
    let level: Float?
    let soundOn: Bool
    let onToggleSound: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        let loud = level.map { $0 >= state.threshold } ?? false
        ZStack {
            Circle()
                .stroke(Color.gray.opacity(0.3), lineWidth: 8)
            Circle()
                .trim(from: 0, to: CGFloat(level ?? 0))
                .stroke(loud ? Color.red : Color.green, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.linear(duration: 0.25), value: level)
            VStack(spacing: 4) {
                if state.crying {
                    Text("Crying!")
                        .font(.title3.bold())
                    Button("Dismiss", action: onDismiss)
                        .tint(.red)
                } else {
                    Text(state.status)
                        .font(.footnote)
                        .multilineTextAlignment(.center)
                    Text(level.map { "\(Int(($0 * 100).rounded()))%" } ?? "—")
                        .font(.title2)
                    Text("Alerts above \(Int((state.threshold * 100).rounded()))%")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                SoundButton(soundOn: soundOn, onToggle: onToggleSound)
            }
            .padding(18)
        }
        .padding(4)
        .background(state.crying ? Color.red.opacity(0.35) : Color.clear)
    }
}

/// Switches the watch's own playback of the sound on and off.
private struct SoundButton: View {
    let soundOn: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            Image(systemName: soundOn ? "speaker.wave.2.fill" : "speaker.slash.fill")
                .font(.footnote)
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.capsule)
        .tint(soundOn ? .green : .gray)
        .frame(width: 56)
        .accessibilityLabel(soundOn ? "Sound on" : "Sound off")
    }
}

private struct MessageView: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(spacing: 8) {
            Text(title)
                .font(.headline)
            Text(detail)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .multilineTextAlignment(.center)
        .padding()
    }
}
