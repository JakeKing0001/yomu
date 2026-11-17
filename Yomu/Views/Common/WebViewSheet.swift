import SwiftUI
import WebKit

/// WebView per aprire un sito e superare i controlli anti-bot (Cloudflare).
/// Alla chiusura i cookie vengono copiati nella sessione usata dalle fonti.
struct WebViewSheet: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""

    var body: some View {
        NavigationStack {
            WebView(url: url, title: $title)
                .ignoresSafeArea(edges: .bottom)
                .navigationTitle(title.isEmpty ? (url.host ?? "") : title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Fatto") {
                            Task {
                                await WebView.syncCookies()
                                dismiss()
                            }
                        }
                    }
                    ToolbarItem(placement: .topBarLeading) {
                        Link(destination: url) { Image(systemName: "safari") }
                    }
                }
        }
    }
}

struct WebView: UIViewRepresentable {
    let url: URL
    @Binding var title: String

    func makeCoordinator() -> Coordinator { Coordinator(title: $title) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        let view = WKWebView(frame: .zero, configuration: config)
        view.customUserAgent = HTTPClient.userAgent
        view.navigationDelegate = context.coordinator
        view.allowsBackForwardNavigationGestures = true
        // Copia nel WebView i cookie già presenti, così la sessione resta coerente.
        let store = config.websiteDataStore.httpCookieStore
        let cookies = HTTPCookieStorage.shared.cookies ?? []
        let group = DispatchGroup()
        for cookie in cookies {
            group.enter()
            store.setCookie(cookie) { group.leave() }
        }
        group.notify(queue: .main) { view.load(URLRequest(url: url)) }
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate {
        var title: Binding<String>
        init(title: Binding<String>) { self.title = title }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            title.wrappedValue = webView.title ?? ""
            Task { await WebView.syncCookies() }
        }
    }

    @MainActor
    static func syncCookies() async {
        let cookies = await WKWebsiteDataStore.default().httpCookieStore.allCookies()
        for cookie in cookies { HTTPCookieStorage.shared.setCookie(cookie) }
    }
}
