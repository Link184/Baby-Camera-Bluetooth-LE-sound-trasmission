import Foundation
import WatchConnectivity

/// The watch side of the link to the iPhone app (WatchBridge.swift there; keys and message types
/// must match). Call `resume()` and `pause()` as the screen comes and goes. Main thread only.
final class PhoneLink: NSObject, ObservableObject, WCSessionDelegate {
    struct State {
        let listening: Bool
        let connected: Bool
        let status: String
        let crying: Bool
        let threshold: Float
    }

    /// The parent unit on the iPhone; nil until the iPhone has sent it once.
    @Published private(set) var state: State?

    /// Sound level from 0 to 1; nil when the iPhone hears nothing (not connected).
    @Published private(set) var level: Float?

    /// When the iPhone app last sent the level; nil before the first.
    @Published private(set) var lastHeard: Date?

    /// Whether the iPhone is in reach; nil until known.
    @Published private(set) var phoneReachable: Bool?

    private var timer: Timer?

    override init() {
        super.init()
        if WCSession.isSupported() {
            WCSession.default.delegate = self
            WCSession.default.activate()
        }
    }

    func resume() {
        guard timer == nil else { return }
        send("watching")
        // The iPhone sends the level for 25 s after each "watching".
        timer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            self?.send("watching")
        }
    }

    func pause() {
        timer?.invalidate()
        timer = nil
        send("stopped")
    }

    func dismiss() {
        send("dismiss")
    }

    private func send(_ type: String) {
        let session = WCSession.default
        guard session.activationState == .activated, session.isReachable else { return }
        session.sendMessage(["type": type], replyHandler: nil, errorHandler: nil)
    }

    private func apply(_ context: [String: Any]) {
        guard let status = context["status"] as? String else { return }
        state = State(
            listening: context["listening"] as? Bool ?? false,
            connected: context["connected"] as? Bool ?? false,
            status: status,
            crying: context["crying"] as? Bool ?? false,
            threshold: (context["threshold"] as? NSNumber)?.floatValue ?? 0.5
        )
    }

    // MARK: - WCSessionDelegate (called on a background queue)

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        let context = session.receivedApplicationContext
        let reachable = session.isReachable
        DispatchQueue.main.async {
            self.apply(context)
            self.phoneReachable = reachable
            if self.timer != nil { self.send("watching") } // resume() came before activation
        }
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        DispatchQueue.main.async { self.apply(applicationContext) }
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        guard let level = (message["level"] as? NSNumber)?.floatValue else { return }
        DispatchQueue.main.async {
            self.level = level < 0 ? nil : level
            self.lastHeard = Date()
        }
    }

    func sessionReachabilityDidChange(_ session: WCSession) {
        let reachable = session.isReachable
        DispatchQueue.main.async {
            self.phoneReachable = reachable
            if reachable && self.timer != nil { self.send("watching") }
        }
    }
}
