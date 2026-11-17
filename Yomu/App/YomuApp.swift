import SwiftUI
import SwiftData
import LocalAuthentication

enum Prefs {
    // Aspetto
    static let theme = "app.theme"                    // 0 sistema, 1 chiaro, 2 scuro
    static let accent = "app.accent"
    static let dateFormat = "app.dateFormat"          // 0 relativo, 1 breve, 2 completo
    // Libreria
    static let libraryDisplay = "library.display"     // 0 griglia compatta, 1 griglia, 2 lista
    static let libraryColumnsPortrait = "library.columns.portrait"
    static let libraryColumnsLandscape = "library.columns.landscape"
    static let librarySort = "library.sort"
    static let librarySortAscending = "library.sort.asc"
    static let libraryFilterUnread = "library.filter.unread"
    static let libraryFilterCompleted = "library.filter.completed"
    static let libraryShowUnreadBadge = "library.badge.unread"
    static let libraryShowDownloadBadge = "library.badge.download"
    static let libraryShowTabs = "library.tabs"
    static let updateOnlyOngoing = "library.update.ongoing"
    static let updateSkipRead = "library.update.skipUnread"
    static let lastLibraryUpdate = "library.lastUpdate"
    static let defaultCategory = "library.defaultCategory"
    // Lettore
    static let readerMode = "reader.mode"
    static let readerKeepScreenOn = "reader.keepScreenOn"
    static let readerShowPageNumber = "reader.pageNumber"
    static let readerBackground = "reader.background"   // 0 nero, 1 bianco, 2 grigio
    static let readerTapZones = "reader.tapZones"
    static let readerInvertTap = "reader.invertTap"
    static let readerWebtoonGap = "reader.webtoonGap"
    static let readerFullscreen = "reader.fullscreen"
    static let readerPreload = "reader.preload"
    static let readerSkipRead = "reader.skipRead"
    // Download
    static let downloadWiFiOnly = "download.wifiOnly"
    static let autoDownloadNew = "download.autoNew"
    static let deleteAfterRead = "download.deleteAfterRead"
    // Sfoglia
    static let enabledLanguages = "browse.languages"
    static let showNSFW = "browse.nsfw"
    static let showUnsupported = "browse.unsupported"
    // Sicurezza
    static let appLock = "security.lock"
    static let secureScreen = "security.secure"
    static let incognito = "security.incognito"
    static let downloadedOnly = "library.downloadedOnly"
    // Avanzate
    static let userAgent = "advanced.userAgent"
}

@main
struct YomuApp: App {
    let container: ModelContainer
    @StateObject private var extensions: ExtensionStore
    @StateObject private var downloads: DownloadManager
    @StateObject private var updater: LibraryUpdater

    init() {
        UserDefaults.standard.register(defaults: [
            Prefs.libraryShowUnreadBadge: true,
            Prefs.libraryShowTabs: true,
            Prefs.readerKeepScreenOn: true,
            Prefs.readerShowPageNumber: true,
            Prefs.readerTapZones: true,
            Prefs.readerFullscreen: true,
            Prefs.readerPreload: 4,
            Prefs.updateOnlyOngoing: false,
            Prefs.libraryColumnsPortrait: 0,
            Prefs.libraryColumnsLandscape: 0,
            Prefs.readerMode: ReaderMode.rightToLeft.rawValue,
        ])
        let container: ModelContainer
        do {
            container = try ModelContainer(for: MangaEntity.self, ChapterEntity.self, CategoryEntity.self)
        } catch {
            fatalError("Impossibile aprire il database: \(error)")
        }
        self.container = container
        let ext = ExtensionStore()
        let dl = DownloadManager(container: container, extensions: ext)
        let up = LibraryUpdater(container: container, extensions: ext)
        up.downloads = dl
        _extensions = StateObject(wrappedValue: ext)
        _downloads = StateObject(wrappedValue: dl)
        _updater = StateObject(wrappedValue: up)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(extensions)
                .environmentObject(downloads)
                .environmentObject(updater)
        }
        .modelContainer(container)
    }
}

enum AccentChoice: Int, CaseIterable {
    case blue, purple, pink, red, orange, green, teal

    var color: Color {
        switch self {
        case .blue: return Color(red: 0.55, green: 0.71, blue: 1.0)
        case .purple: return .purple
        case .pink: return .pink
        case .red: return .red
        case .orange: return .orange
        case .green: return .green
        case .teal: return .teal
        }
    }

    var label: String {
        switch self {
        case .blue: return "Blu (Mihon)"
        case .purple: return "Viola"
        case .pink: return "Rosa"
        case .red: return "Rosso"
        case .orange: return "Arancione"
        case .green: return "Verde"
        case .teal: return "Verde acqua"
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var extensions: ExtensionStore
    @AppStorage(Prefs.theme) private var theme = 0
    @AppStorage(Prefs.accent) private var accent = 0
    @AppStorage(Prefs.appLock) private var appLock = false
    @AppStorage(Prefs.secureScreen) private var secureScreen = false
    @Environment(\.scenePhase) private var scenePhase
    @State private var locked = false
    @State private var tab = 0

    var body: some View {
        ZStack {
            TabView(selection: $tab) {
                LibraryView()
                    .tabItem { Label("Libreria", systemImage: "books.vertical") }
                    .tag(0)
                UpdatesView()
                    .tabItem { Label("Novità", systemImage: "exclamationmark.circle") }
                    .tag(1)
                HistoryView()
                    .tabItem { Label("Cronologia", systemImage: "clock.arrow.circlepath") }
                    .tag(2)
                BrowseView()
                    .tabItem { Label("Sfoglia", systemImage: "safari") }
                    .badge(extensions.updatableCount)
                    .tag(3)
                MoreView()
                    .tabItem { Label("Altro", systemImage: "ellipsis") }
                    .tag(4)
            }
            if locked {
                LockView { locked = false }
                    .transition(.opacity)
            } else if secureScreen && scenePhase != .active {
                PrivacyCover()
            }
        }
        .tint(AccentChoice(rawValue: accent)?.color ?? AccentChoice.blue.color)
        .preferredColorScheme(theme == 1 ? .light : theme == 2 ? .dark : nil)
        .onAppear {
            if appLock { locked = true }
            Task { await extensions.refresh() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background && appLock { locked = true }
        }
    }
}

struct PrivacyCover: View {
    var body: some View {
        ZStack {
            Rectangle().fill(.ultraThinMaterial).ignoresSafeArea()
            Image(systemName: "book.closed.fill").font(.system(size: 60)).foregroundStyle(.secondary)
        }
    }
}

struct LockView: View {
    var onUnlock: () -> Void
    @State private var message: String?

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            VStack(spacing: 20) {
                Image(systemName: "lock.fill").font(.system(size: 50))
                Text("Yomu è bloccata").font(.title2.bold())
                if let message { Text(message).foregroundStyle(.secondary) }
                Button("Sblocca") { authenticate() }
                    .buttonStyle(.borderedProminent)
            }
        }
        .onAppear(perform: authenticate)
    }

    private func authenticate() {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            onUnlock()
            return
        }
        context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Sblocca Yomu") { success, err in
            DispatchQueue.main.async {
                if success { onUnlock() } else { message = err?.localizedDescription }
            }
        }
    }
}
