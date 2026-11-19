import Foundation
import Shared
import WatchConnectivity

/// The iPhone side of the Apple Watch app link, for `IosWatchLink` in the shared code.
///
/// - The parent unit's state goes in the application context: WatchConnectivity keeps the latest
///   one and hands it to the watch app whenever it runs.
/// - The sound level goes as messages, only while the watch app is on screen: it sends "watching"
///   every 10 seconds then, and "stopped" when it leaves.
/// - "dismiss" from the watch dismisses the crying alert.
///
/// The keys and message types must match PhoneLink.swift in the watch app.
final class WatchBridge: NSObject, IosWatchBridge, WCSessionDelegate {
    private var listener: IosWatchListener?

    /// Until when the watch wants the sound level.
    private var watchingUntil = Date.distantPast

    func start(listener: IosWatchListener) {
        self.listener = listener
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    func stop() {
        listener = nil
    }

    /// 0: sent, 1: try again later, 2: this device can't have a watch (IosWatchBridge.PUBLISH_*).
    func publish(listening: Bool, connected: Bool, status: String, crying: Bool, threshold: Float) -> Int32 {
        guard WCSession.isSupported() else { return 2 }
        let session = WCSession.default
        guard session.activationState == .activated, session.isPaired, session.isWatchAppInstalled else { return 1 }
        do {
            try session.updateApplicationContext([
                "listening": listening,
                "connected": connected,
                "status": status,
                "crying": crying,
                "threshold": threshold,
            ])
            return 0
        } catch {
            return 1
        }
    }

    func isWatching() -> Bool {
        WCSession.isSupported() && WCSession.default.isReachable && Date() < watchingUntil
    }

    /// From 0 to 1, or negative for none.
    func sendLevel(level: Float) {
        WCSession.default.sendMessage(["level": level], replyHandler: nil, errorHandler: nil)
    }

    // MARK: - WCSessionDelegate (called on a background queue)

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {}

    func sessionDidBecomeInactive(_ session: WCSession) {}

    func sessionDidDeactivate(_ session: WCSession) {
        session.activate() // the user switched to another watch
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        let type = message["type"] as? String
        DispatchQueue.main.async {
            switch type {
            case "watching": self.watchingUntil = Date().addingTimeInterval(25)
            case "stopped": self.watchingUntil = .distantPast
            case "dismiss": self.listener?.onDismiss()
            default: break
            }
        }
    }
}
