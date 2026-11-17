import SwiftUI
import SwiftData

struct ReaderTarget: Identifiable {
    let mangaKey: String
    let chapterKey: String
    var id: String { chapterKey }
}

struct MangaDetailView: View {
    private let sourceId: String
    private let url: String
    private let initial: SManga?

    @Environment(\.modelContext) private var context
    @EnvironmentObject private var extensions: ExtensionStore
    @EnvironmentObject private var downloads: DownloadManager

    @State private var manga: MangaEntity? = nil
    @State private var loading = false
    @State private var error: Error? = nil
    @State private var expanded = false
    @State private var reader: ReaderTarget?
    @State private var webURL: URL?
    @State private var showCategories = false
    @State private var chapterFilter = 0  // 0 tutti, 1 non letti, 2 scaricati, 3 segnalibri

    init(entity: MangaEntity) {
        sourceId = entity.sourceId
        url = entity.url
        initial = nil
        _manga = State(initialValue: entity)
    }

    init(sourceId: String, manga: SManga) {
        self.sourceId = sourceId
        self.url = manga.url
        self.initial = manga
    }

    private var source: MangaSource? { extensions.source(id: sourceId) }

    private var chapters: [ChapterEntity] {
        guard let manga else { return [] }
        var list = manga.sortedChapters
        if manga.chapterSortAscending { list.reverse() }
        switch chapterFilter {
        case 1: list = list.filter { !$0.read }
        case 2: list = list.filter(\.isDownloaded)
        case 3: list = list.filter(\.bookmarked)
        default: break
        }
        return list
    }

    var body: some View {
        List {
            header
                .listRowInsets(EdgeInsets())
                .listRowSeparator(.hidden)
            if let error {
                ErrorBanner(error: error) { Task { await refresh() } } openWeb: { webURL = $0 }
                    .listRowSeparator(.hidden)
            }
            Section {
                ForEach(chapters) { chapter in
                    ChapterRow(chapter: chapter, queued: downloads.isQueued(chapter)) {
                        open(chapter)
                    } download: {
                        if let manga { downloads.enqueue([chapter], manga: manga) }
                    } deleteDownload: {
                        downloads.delete([chapter])
                    }
                    .swipeActions(edge: .leading) {
                        Button {
                            chapter.bookmarked.toggle()
                            try? context.save()
                        } label: {
                            Label("Segnalibro", systemImage: chapter.bookmarked ? "bookmark.slash" : "bookmark")
                        }
                        .tint(.orange)
                    }
                    .swipeActions(edge: .trailing) {
                        Button {
                            MangaRepository.setRead([chapter], read: !chapter.read)
                            try? context.save()
                        } label: {
                            Label(chapter.read ? "Non letto" : "Letto", systemImage: chapter.read ? "eye.slash" : "eye")
                        }
                        .tint(.accentColor)
                    }
                    .contextMenu { chapterMenu(chapter) }
                }
            } header: {
                chapterHeader
            }
        }
        .listStyle(.plain)
        .navigationTitle(manga?.title ?? initial?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbar }
        .refreshable { await refresh() }
        .overlay {
            if loading && (manga?.chapters.isEmpty ?? true) { ProgressView() }
        }
        .overlay(alignment: .bottomTrailing) {
            if let manga, let next = manga.nextChapterToRead {
                Button {
                    open(next)
                } label: {
                    Label(manga.chapters.contains(where: \.read) || next.lastPageRead > 0 ? "Riprendi" : "Inizia", systemImage: "play.fill")
                        .font(.headline)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 14)
                        .background(Color.accentColor, in: Capsule())
                        .foregroundStyle(.black)
                }
                .padding()
                .shadow(radius: 4)
            }
        }
        .task { await load() }
        .fullScreenCover(item: $reader) { target in
            ReaderView(mangaKey: target.mangaKey, chapterKey: target.chapterKey)
        }
        .sheet(item: Binding(get: { webURL.map { IdentifiedURL(url: $0) } }, set: { webURL = $0?.url })) { item in
            WebViewSheet(url: item.url)
        }
        .sheet(isPresented: $showCategories) {
            if let manga { CategoryPickerSheet(manga: manga) }
        }
    }

    // MARK: Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 14) {
                CoverImage(url: manga?.thumbnailURL ?? initial?.thumbnailURL, headers: source?.coverHeaders ?? [:])
                    .frame(width: 110)
                VStack(alignment: .leading, spacing: 6) {
                    Text(manga?.title ?? initial?.title ?? "")
                        .font(.title3.bold())
                        .textSelection(.enabled)
                    if let author = manga?.author, !author.isEmpty {
                        Label(author, systemImage: "person").font(.subheadline).foregroundStyle(.secondary)
                    }
                    if let artist = manga?.artist, !artist.isEmpty, artist != manga?.author {
                        Label(artist, systemImage: "paintbrush").font(.subheadline).foregroundStyle(.secondary)
                    }
                    if let status = manga?.statusValue {
                        Label("\(status.label) • \(manga?.sourceName ?? "")", systemImage: status.icon)
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            HStack(spacing: 0) {
                actionButton(
                    manga?.inLibrary == true ? "In libreria" : "Aggiungi alla libreria",
                    icon: manga?.inLibrary == true ? "heart.fill" : "heart",
                    active: manga?.inLibrary == true
                ) { toggleLibrary() }
                actionButton("WebView", icon: "globe", active: false) {
                    if let s = source, let m = manga {
                        webURL = s.mangaWebURL(SManga(url: m.url, title: m.title))
                    }
                }
                if manga?.inLibrary == true {
                    actionButton("Categorie", icon: "tag", active: false) { showCategories = true }
                }
            }
            if let summary = manga?.summary, !summary.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text(summary)
                        .font(.subheadline)
                        .lineLimit(expanded ? nil : 3)
                        .textSelection(.enabled)
                    Button(expanded ? "Mostra meno" : "Mostra altro") { withAnimation { expanded.toggle() } }
                        .font(.caption.bold())
                }
            }
            if let genres = manga?.genres, !genres.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(genres, id: \.self) { g in
                            Text(g).font(.caption)
                                .padding(.horizontal, 10).padding(.vertical, 5)
                                .overlay(Capsule().stroke(Color.secondary.opacity(0.5)))
                        }
                    }
                }
            }
        }
        .padding()
    }

    private func actionButton(_ title: String, icon: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.title3)
                Text(title).font(.caption).multilineTextAlignment(.center)
            }
            .foregroundStyle(active ? Color.accentColor : .secondary)
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderless)
    }

    private var chapterHeader: some View {
        HStack {
            Text("\(manga?.chapters.count ?? 0) capitoli").font(.subheadline.bold())
            Spacer()
            Menu {
                Picker("Filtro", selection: $chapterFilter) {
                    Text("Tutti").tag(0)
                    Text("Non letti").tag(1)
                    Text("Scaricati").tag(2)
                    Text("Segnalibri").tag(3)
                }
                Button(manga?.chapterSortAscending == true ? "Dal più recente" : "Dal più vecchio", systemImage: "arrow.up.arrow.down") {
                    manga?.chapterSortAscending.toggle()
                }
            } label: {
                Image(systemName: "line.3.horizontal.decrease.circle")
            }
        }
        .padding(.vertical, 4)
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                Button("Scarica i prossimi 5", systemImage: "arrow.down.circle") { downloadNext(5) }
                Button("Scarica i prossimi 10", systemImage: "arrow.down.circle") { downloadNext(10) }
                Button("Scarica i non letti", systemImage: "arrow.down.circle.dotted") { downloadNext(nil) }
                Button("Scarica tutto", systemImage: "square.and.arrow.down.on.square") {
                    if let manga { downloads.enqueue(manga.chapters, manga: manga) }
                }
                Divider()
                Button("Segna tutto come letto", systemImage: "checkmark.circle") {
                    if let manga { MangaRepository.setRead(manga.chapters, read: true); try? context.save() }
                }
                Button("Segna tutto come non letto", systemImage: "circle") {
                    if let manga { MangaRepository.setRead(manga.chapters, read: false); try? context.save() }
                }
                Button("Elimina download", systemImage: "trash", role: .destructive) {
                    if let manga { downloads.delete(manga.chapters.filter(\.isDownloaded)) }
                }
            } label: {
                Image(systemName: "arrow.down.to.line.circle")
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            if let s = source, let m = manga, let link = s.mangaWebURL(SManga(url: m.url, title: m.title)) {
                ShareLink(item: link)
            }
        }
    }

    @ViewBuilder
    private func chapterMenu(_ chapter: ChapterEntity) -> some View {
        Button("Segna i precedenti come letti", systemImage: "checkmark.circle.badge.questionmark") {
            if let manga {
                let older = manga.chapters.filter { $0.sourceOrder > chapter.sourceOrder }
                MangaRepository.setRead(older, read: true)
                try? context.save()
            }
        }
        if chapter.isDownloaded {
            Button("Elimina download", systemImage: "trash", role: .destructive) { downloads.delete([chapter]) }
        } else {
            Button("Scarica", systemImage: "arrow.down.circle") {
                if let manga { downloads.enqueue([chapter], manga: manga) }
            }
        }
        if let s = source, let link = s.chapterWebURL(SChapter(url: chapter.url, name: chapter.name)) {
            Button("Apri nel browser", systemImage: "safari") { webURL = link }
        }
    }

    // MARK: Azioni

    private func load() async {
        if manga == nil {
            let key = MangaEntity.makeKey(sourceId: sourceId, url: url)
            if let existing = MangaRepository.manga(key: key, in: context) {
                manga = existing
            } else if let initial {
                let info = source?.info ?? SourceInfo(id: sourceId, name: "", lang: "", baseURL: "")
                manga = MangaRepository.upsert(initial, source: info, in: context)
                try? context.save()
            }
        }
        if let manga, manga.chapters.isEmpty || manga.summary == nil {
            await refresh()
        }
    }

    private func refresh() async {
        guard let manga else { return }
        guard let source else {
            error = SourceError.sourceUnavailable
            return
        }
        loading = true
        defer { loading = false }
        do {
            _ = try await MangaRepository.refresh(manga, source: source, in: context)
            error = nil
        } catch {
            self.error = error
        }
    }

    private func toggleLibrary() {
        guard let manga else { return }
        manga.inLibrary.toggle()
        if manga.inLibrary {
            manga.dateAdded = Date()
            let def = UserDefaults.standard.string(forKey: Prefs.defaultCategory) ?? ""
            if !def.isEmpty, manga.categoryIDs.isEmpty { manga.categoryIDs = [def] }
        }
        try? context.save()
    }

    private func open(_ chapter: ChapterEntity) {
        guard let manga else { return }
        reader = ReaderTarget(mangaKey: manga.key, chapterKey: chapter.key)
    }

    private func downloadNext(_ count: Int?) {
        guard let manga else { return }
        let unread = manga.chapters.filter { !$0.read && !$0.isDownloaded }.sorted { $0.sourceOrder > $1.sourceOrder }
        downloads.enqueue(count.map { Array(unread.prefix($0)) } ?? unread, manga: manga)
    }
}

struct IdentifiedURL: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

struct ChapterRow: View {
    let chapter: ChapterEntity
    let queued: DownloadManager.Item?
    var open: () -> Void
    var download: () -> Void
    var deleteDownload: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Button(action: open) {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 4) {
                        if chapter.bookmarked {
                            Image(systemName: "bookmark.fill").font(.caption).foregroundStyle(Color.accentColor)
                        }
                        Text(chapter.name).font(.subheadline).lineLimit(2)
                    }
                    HStack(spacing: 6) {
                        if let d = chapter.dateUpload { Text(DateText.short(d)) }
                        if chapter.lastPageRead > 0 && !chapter.read { Text("• Pagina \(chapter.lastPageRead + 1)") }
                        if let s = chapter.scanlator { Text("• \(s)").lineLimit(1) }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .opacity(chapter.read ? 0.45 : 1)

            Group {
                if chapter.isDownloaded {
                    Button(action: deleteDownload) { Image(systemName: "checkmark.circle.fill") }
                } else if let queued {
                    if queued.error != nil {
                        Image(systemName: "exclamationmark.circle").foregroundStyle(.red)
                    } else {
                        ProgressView(value: queued.progress).progressViewStyle(.circular).frame(width: 22, height: 22)
                    }
                } else {
                    Button(action: download) { Image(systemName: "arrow.down.circle") }
                }
            }
            .buttonStyle(.borderless)
            .foregroundStyle(.secondary)
            .font(.title3)
        }
    }
}

struct ErrorBanner: View {
    let error: Error
    var retry: () -> Void
    var openWeb: (URL) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(error.localizedDescription, systemImage: "exclamationmark.triangle")
                .font(.subheadline)
            HStack {
                Button("Riprova", action: retry).buttonStyle(.bordered)
                if let url = (error as? SourceError)?.cloudflareURL {
                    Button("Apri in WebView") { openWeb(url) }.buttonStyle(.borderedProminent)
                }
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.red.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
    }
}

enum DateText {
    static func short(_ date: Date) -> String {
        let mode = UserDefaults.standard.integer(forKey: Prefs.dateFormat)
        switch mode {
        case 1: return date.formatted(date: .numeric, time: .omitted)
        case 2: return date.formatted(date: .long, time: .omitted)
        default:
            let days = Calendar.current.dateComponents([.day], from: Calendar.current.startOfDay(for: date), to: Calendar.current.startOfDay(for: Date())).day ?? 0
            if days == 0 { return "Oggi" }
            if days == 1 { return "Ieri" }
            if days < 7 && days > 0 { return "\(days) giorni fa" }
            return date.formatted(date: .numeric, time: .omitted)
        }
    }

    static func header(_ date: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(date) { return "Oggi" }
        if cal.isDateInYesterday(date) { return "Ieri" }
        return date.formatted(date: .numeric, time: .omitted)
    }
}
