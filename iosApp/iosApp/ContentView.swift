import Shared
import SwiftUI

/// Hosts the shared Compose UI.
struct ComposeView: UIViewControllerRepresentable {
    let bridge: BleBridge
    let watch: WatchBridge

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(bridge: bridge, secrets: KeychainSecretStore(), watch: watch)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    @State private var bridge = BleBridge()
    @State private var watch = WatchBridge()

    var body: some View {
        ComposeView(bridge: bridge, watch: watch)
            .ignoresSafeArea(.keyboard)
    }
}
