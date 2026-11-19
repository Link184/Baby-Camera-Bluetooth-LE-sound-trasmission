import SwiftUI

/// The Apple Watch app: shows what the BabyCamera parent unit on the paired iPhone hears.
@main
struct BabyCameraWatchApp: App {
    var body: some Scene {
        WindowGroup {
            WatchView()
        }
    }
}
