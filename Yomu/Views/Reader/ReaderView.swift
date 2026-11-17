import SwiftUI
import SwiftData

enum ReaderMode: Int, CaseIterable {
    case rightToLeft = 0, leftToRight, vertical, webtoon

    var label: String {
        switch self {
        case .rightToLeft: return "Paginato (da destra a sinistra)"
        case .leftToRight: return "Paginato (da sinistra a destra)"
        case .vertical: return "Verticale continuo"
        case .webtoon: return "Webtoon"
        }
    }

    var icon: String {
        switch self {
        case .rightToLeft: return "arrow.left.to.line"
        case .leftToRight: return "arrow.right.to.line"
        case .vertical: return "arrow.down.to.line"
        case .webtoon: return "rectangle.portrait.arrowtriangle.2.outward"
        }
    }

    var isContinuous: Bool { self == .vertical || self == .webtoon }
}

struct ReaderView: View {
    let mangaKey: String
    @State private var chapterKey: String

    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var extensions: ExtensionStore
    @EnvironmentObject private var downloads: DownloadManager

    @AppStorage(Prefs.readerMode) private var defaultMode = ReaderMode.rightToLeft.rawValue
    @AppStorage(Prefs.readerKeepScreenOn) private var keepScreenOn = true
    @AppStorage(Prefs.readerShowPageNumber) private var showPageNumber = true
    @AppStorage(Prefs.readerBackground) private var background = 0
    @AppStorage(Prefs.readerTapZones) private var tapZones = true
    @AppStorage(Prefs.readerInvertTap) private var invertTap = false
    @AppStorage(Prefs.readerWebtoonGap) private var webtoonGap = 0.0
    @AppStorage(Prefs.readerPreload) private var preload = 4
    @AppStorage(Prefs.incognito) private var incognito = false
    @AppStorage(Prefs.deleteAfterRead) private var deleteAfterRead = false

    @State private var manga: MangaEntity?
    @State private var chapter: ChapterEntity?
    @State private var pages: [PageItem] = []
    @State private var current = 0
    @State private var loading = true
    @State private var error: Error?
    @State private var showMenu = false
    @State private var jump: Int?
    @State private var webURL: URL?
    @State private var sliderValue = 0.0
    @State private var dragging = false

    init(mangaKey: String, chapterKey: String) {
        self.mangaKey = mangaKey
        _chapterKey = State(initialValue: chapterKey)
    }

    private var source: MangaSource? { manga.flatMap { extensions.source(id: $0.sourceId) } }

    private var mode: ReaderMode {
        if let m = manga, m.readerMode >= 0, let r = ReaderMode(rawValue: m.readerMode) { return r }
        return ReaderMode(rawValue: defaultMode) ?? .rightToLeft
    }

    private var backgroundColor: Color {
        switch background {
        case 1: return .white
        case 2: return Color(white: 0.2)
        default: return .black
        }
    }

    private var headers: (PageItem) -> [String: String] {
        let s = source
        return { page in page.imageURL.hasPrefix("file://") ? [:] : (s?.imageHeaders(for: page) ?? [:]) }
    }

    /// Capitoli in ordine di lettura (dal più vecchio al più recente).
    private var readingOrder: [ChapterEntity] {
        (manga?.chapters ?? []).sorted { $0.sourceOrder > $1.sourceOrder }
    }

    private var nextChapter: ChapterEntity? {
        guard let chapter, let i = readingOrder.firstIndex(where: { $0.key == chapter.key }), i + 1 < readingOrder.count else { return nil }
        return readingOrder[i + 1]
    }

    private var previousChapter: ChapterEntity? {
        guard let chapter, let i = readingOrder.firstIndex(where: { $0.key == chapter.key }), i > 0 else { return nil }
        return readingOrder[i - 1]
    }

    var body: some View {
        ZStack {
            backgroundColor.ignoresSafeArea()
            content
            if showMenu {
                menuOverlay
            } else if showPageNumber && !pages.isEmpty {
                VStack {
                    Spacer()
                    Text("\(min(current, pages.count - 1) + 1) / \(pages.count)")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.white)
                        .shadow(color: .black, radius: 2)
                        .padding(.bottom, 4)
                }
                .allowsHitTesting(false)
            }
        }
        .statusBarHidden(!showMenu)
        .persistentSystemOverlays(showMenu ? .automatic : .hidden)
        .task(id: chapterKey) { await loadChapter() }
        .onChange(of: current) { _, value in
            if !dragging { sliderValue = Double(min(value, max(0, pages.count - 1))) }
            saveProgress()
            prefetch(from: value)
        }
        .onAppear { UIApplication.shared.isIdleTimerDisabled = keepScreenOn }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
            saveProgress()
            cleanupAfterRead(chapter)
        }
        .sheet(item: Binding(get: { webURL.map { IdentifiedURL(url: $0) } }, set: { webURL = $0?.url }), onDismiss: {
            Task { await loadChapter() }
        }) { item in
            WebViewSheet(url: item.url)
        }
    }

    @ViewBuilder
    private var content: some View {
        if loading {
            ProgressView().tint(.white)
        } else if let error {
            VStack(spacing: 14) {
                Image(systemName: "exclamationmark.triangle").font(.largeTitle)
                Text(error.localizedDescription).multilineTextAlignment(.center)
                HStack {
                    Button("Riprova") { Task { await loadChapter() } }.buttonStyle(.borderedProminent)
                    if let url = (error as? SourceError)?.cloudflareURL {
                        Button("Apri in WebView") { webURL = url }.buttonStyle(.bordered)
                    }
                    Button("Chiudi") { dismiss() }.buttonStyle(.bordered)
                }
            }
            .foregroundStyle(.white)
            .padding()
        } else if pages.isEmpty {
            VStack(spacing: 14) {
                Text("Nessuna pagina trovata").foregroundStyle(.white)
                Button("Chiudi") { dismiss() }.buttonStyle(.bordered)
            }
        } else if mode.isContinuous {
            WebtoonReader(
                pages: pages,
                headers: headers,
                gap: mode == .vertical ? max(8, webtoonGap) : webtoonGap,
                current: $current,
                jump: $jump,
                onTap: { showMenu.toggle() },
                transition: { transitionView }
            )
            .id(chapterKey)
        } else {
            PagedReader(
                pages: pages,
                headers: headers,
                rightToLeft: mode == .rightToLeft,
                current: $current,
                onTap: handleTap,
                transition: { transitionView }
            )
            .id(chapterKey)
        }
    }

    private var transitionView: some View {
        VStack(spacing: 18) {
            if let chapter {
                Text("Finito:").font(.caption).foregroundStyle(.secondary)
                Text(chapter.name).font(.headline)
            }
            Divider().frame(width: 120)
            if let next = nextChapter {
                Text("Successivo:").font(.caption).foregroundStyle(.secondary)
                Text(next.name).font(.headline)
                Button("Leggi il prossimo capitolo") { switchTo(next) }
                    .buttonStyle(.borderedProminent)
            } else {
                Text("Non ci sono altri capitoli").foregroundStyle(.secondary)
                Button("Chiudi") { dismiss() }.buttonStyle(.bordered)
            }
        }
        .foregroundStyle(background == 1 ? .black : .white)
        .multilineTextAlignment(.center)
        .padding(40)
        .frame(maxWidth: .infinity, minHeight: 400)
    }

    // MARK: Menu

    private var menuOverlay: some View {
        VStack(spacing: 0) {
            HStack(spacing: 14) {
                Button { dismiss() } label: { Image(systemName: "xmark").font(.title3.bold()) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(manga?.title ?? "").font(.headline).lineLimit(1)
                    Text(chapter?.name ?? "").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                Button {
                    chapter?.bookmarked.toggle()
                    try? context.save()
                } label: {
                    Image(systemName: chapter?.bookmarked == true ? "bookmark.fill" : "bookmark")
                }
                Menu {
                    ForEach(ReaderMode.allCases, id: \.rawValue) { m in
                        Button {
                            manga?.readerMode = m.rawValue
                            try? context.save()
                        } label: {
                            Label(m.label, systemImage: mode == m ? "checkmark" : m.icon)
                        }
                    }
                    Button("Usa modalità predefinita") {
                        manga?.readerMode = -1
                        try? context.save()
                    }
                } label: {
                    Image(systemName: mode.icon)
                }
                if let s = source, let c = chapter, let url = s.chapterWebURL(SChapter(url: c.url, name: c.name)) {
                    Button { webURL = url } label: { Image(systemName: "globe") }
                }
            }
            .padding()
            .background(.ultraThinMaterial)

            Spacer()
                .contentShape(Rectangle())
                .onTapGesture { showMenu = false }

            HStack(spacing: 12) {
                Button { if let p = previousChapter { switchTo(p) } } label: {
                    Image(systemName: "backward.end.fill")
                }
                .disabled(previousChapter == nil)
                Text("\(Int(sliderValue) + 1)").font(.caption.monospacedDigit())
                if pages.count > 1 {
                    Slider(value: $sliderValue, in: 0...Double(pages.count - 1), step: 1, onEditingChanged: { editing in
                        dragging = editing
                        if !editing {
                            let target = Int(sliderValue)
                            if mode.isContinuous { jump = target } else { withAnimation { current = target } }
                        }
                    })
                    .environment(\.layoutDirection, mode == .rightToLeft ? .rightToLeft : .leftToRight)
                }
                Text("\(pages.count)").font(.caption.monospacedDigit())
                Button { if let n = nextChapter { switchTo(n) } } label: {
                    Image(systemName: "forward.end.fill")
                }
                .disabled(nextChapter == nil)
            }
            .padding()
            .background(.ultraThinMaterial)
        }
        .foregroundStyle(.primary)
    }

    // MARK: Logica

    private func handleTap(_ location: CGPoint, _ size: CGSize) {
        guard tapZones, size.width > 0 else { showMenu.toggle(); return }
        let x = location.x / size.width
        var forwardOnLeft = mode == .rightToLeft
        if invertTap { forwardOnLeft.toggle() }
        if x < 0.33 {
            step(forwardOnLeft ? 1 : -1)
        } else if x > 0.67 {
            step(forwardOnLeft ? -1 : 1)
        } else {
            showMenu.toggle()
        }
    }

    private func step(_ delta: Int) {
        if showMenu { showMenu = false; return }
        let target = current + delta
        if target < 0 {
            if let p = previousChapter { switchTo(p) }
            return
        }
        withAnimation(.easeInOut(duration: 0.2)) { current = min(target, pages.count) }
    }

    private func switchTo(_ next: ChapterEntity) {
        saveProgress()
        cleanupAfterRead(chapter)
        showMenu = false
        chapterKey = next.key
    }

    private func loadChapter() async {
        loading = true
        error = nil
        defer { loading = false }
        if manga == nil { manga = MangaRepository.manga(key: mangaKey, in: context) }
        let key = chapterKey
        var d = FetchDescriptor<ChapterEntity>(predicate: #Predicate { $0.key == key })
        d.fetchLimit = 1
        guard let c = try? context.fetch(d).first else {
            error = SourceError.parse("capitolo non trovato")
            return
        }
        chapter = c
        pages = []
        if c.isDownloaded, let local = DownloadManager.localPages(for: c) {
            pages = local
        } else {
            guard let source else {
                error = SourceError.sourceUnavailable
                return
            }
            do {
                pages = try await source.pages(SChapter(url: c.url, name: c.name))
            } catch {
                self.error = error
                return
            }
        }
        let start = c.read ? 0 : min(c.lastPageRead, max(0, pages.count - 1))
        current = start
        sliderValue = Double(start)
        if mode.isContinuous && start > 0 { jump = start }
        prefetch(from: start)
        if !incognito {
            c.lastReadAt = Date()
            try? context.save()
        }
    }

    private func saveProgress() {
        guard let chapter, !pages.isEmpty else { return }
        chapter.lastPageRead = min(current, pages.count - 1)
        if current >= pages.count - 1 {
            chapter.read = true
            chapter.isNew = false
        }
        if !incognito { chapter.lastReadAt = Date() }
        try? context.save()
    }

    private func cleanupAfterRead(_ chapter: ChapterEntity?) {
        guard deleteAfterRead, let chapter, chapter.read, chapter.isDownloaded else { return }
        downloads.delete([chapter])
    }

    private func prefetch(from index: Int) {
        guard !pages.isEmpty else { return }
        let upper = min(pages.count - 1, index + max(1, preload))
        guard index + 1 <= upper else { return }
        let items = Array(pages[(index + 1)...upper])
        let h = headers
        Task.detached(priority: .utility) {
            for page in items {
                _ = try? await ImageLoader.shared.image(url: page.imageURL, headers: h(page))
            }
        }
    }
}

// MARK: Paginato

struct PagedReader<Transition: View>: View {
    let pages: [PageItem]
    let headers: (PageItem) -> [String: String]
    let rightToLeft: Bool
    @Binding var current: Int
    var onTap: (CGPoint, CGSize) -> Void
    @ViewBuilder var transition: () -> Transition

    var body: some View {
        // Per la lettura da destra a sinistra le pagine sono in ordine inverso:
        // la prima pagina è a destra e si scorre verso sinistra.
        let indices = Array(0...pages.count)
        TabView(selection: $current) {
            ForEach(rightToLeft ? indices.reversed() : indices, id: \.self) { i in
                Group {
                    if i < pages.count {
                        PageImageView(page: pages[i], headers: headers(pages[i]), onTap: onTap)
                    } else {
                        GeometryReader { geo in
                            transition()
                                .frame(width: geo.size.width, height: geo.size.height)
                                .contentShape(Rectangle())
                                .onTapGesture { location in onTap(location, geo.size) }
                        }
                    }
                }
                .tag(i)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .ignoresSafeArea()
    }
}

struct PageImageView: View {
    let page: PageItem
    let headers: [String: String]
    var onTap: (CGPoint, CGSize) -> Void

    @State private var image: UIImage?
    @State private var failed: Error?
    @State private var attempt = 0

    var body: some View {
        GeometryReader { geo in
            ZStack {
                if let image {
                    ZoomableImage(image: image, onTap: onTap)
                } else {
                    Color.clear
                        .contentShape(Rectangle())
                        .onTapGesture { location in onTap(location, geo.size) }
                    if let failed {
                        VStack(spacing: 10) {
                            Text("Pagina \(page.index + 1)").font(.headline)
                            Text(failed.localizedDescription).font(.caption).multilineTextAlignment(.center)
                            Button("Riprova") { attempt += 1 }.buttonStyle(.bordered)
                        }
                        .foregroundStyle(.white)
                        .padding()
                    } else {
                        VStack(spacing: 8) {
                            ProgressView().tint(.white)
                            Text("\(page.index + 1)").font(.caption).foregroundStyle(.gray)
                        }
                    }
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .task(id: "\(page.imageURL)#\(attempt)") {
            do {
                failed = nil
                image = try await ImageLoader.shared.image(url: page.imageURL, headers: headers)
            } catch {
                failed = error
            }
        }
    }
}

// MARK: Webtoon / verticale

struct WebtoonReader<Transition: View>: View {
    let pages: [PageItem]
    let headers: (PageItem) -> [String: String]
    let gap: Double
    @Binding var current: Int
    @Binding var jump: Int?
    var onTap: () -> Void
    @ViewBuilder var transition: () -> Transition

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: gap) {
                    ForEach(pages) { page in
                        WebtoonPageView(page: page, headers: headers(page))
                            .id(page.index)
                            .onAppear { current = page.index }
                    }
                    transition()
                        .id(pages.count)
                        .onAppear { current = pages.count }
                }
            }
            .onTapGesture { onTap() }
            .onChange(of: jump) { _, target in
                guard let target else { return }
                proxy.scrollTo(target, anchor: .top)
                jump = nil
            }
            .onAppear {
                if let target = jump {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                        proxy.scrollTo(target, anchor: .top)
                        jump = nil
                    }
                }
            }
        }
        .ignoresSafeArea()
    }
}

struct WebtoonPageView: View {
    let page: PageItem
    let headers: [String: String]
    @State private var image: UIImage?
    @State private var failed: Error?
    @State private var attempt = 0

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(image.size, contentMode: .fit)
                    .frame(maxWidth: .infinity)
            } else if let failed {
                VStack(spacing: 10) {
                    Text("Pagina \(page.index + 1)").font(.headline)
                    Text(failed.localizedDescription).font(.caption)
                    Button("Riprova") { attempt += 1 }.buttonStyle(.bordered)
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 300)
            } else {
                ProgressView().tint(.white)
                    .frame(maxWidth: .infinity, minHeight: 500)
            }
        }
        .task(id: "\(page.imageURL)#\(attempt)") {
            do {
                failed = nil
                image = try await ImageLoader.shared.image(url: page.imageURL, headers: headers)
            } catch {
                failed = error
            }
        }
    }
}
