import Shared
import SwiftUI

/// Hosts the shared Compose UI.
struct ComposeView: UIViewControllerRepresentable {
    let bridge: BleBridge

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(bridge: bridge, secrets: KeychainSecretStore())
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    @State private var bridge = BleBridge()

    var body: some View {
        ComposeView(bridge: bridge)
            .ignoresSafeArea(.keyboard)
    }
}
