import SwiftUI
import SwiftData

struct UpdatesView: View {
    @Environment(\.modelContext) private var context
    @EnvironmentObject private var updater: LibraryUpdater
    @EnvironmentObject private var downloads: DownloadManager
    @EnvironmentObject private var extensions: ExtensionStore
    @Query(filter: #Predicate<ChapterEntity> { $0.isNew == true }) private var newChapters: [ChapterEntity]
    @State private var reader: ReaderTarget?
    @State private var unreadOnly = false
    @State private var showErrors = false

    private var groups: [(Date, [ChapterEntity])] {
        let limit = Calendar.current.date(byAdding: .month, value: -3, to: Date()) ?? .distantPast
        let list = newChapters
            .filter { $0.manga?.inLibrary == true && $0.dateFetched > limit && (!unreadOnly || !$0.read) }
            .sorted { a, b in
                // Più recenti prima; a parità di data (stesso aggiornamento) si raggruppa per manga
                // e si mostra prima il capitolo più nuovo, come in Mihon.
                if a.dateFetched != b.dateFetched { return a.dateFetched > b.dateFetched }
                let ta = a.manga?.title ?? "", tb = b.manga?.title ?? ""
                if ta != tb { return ta.localizedCaseInsensitiveCompare(tb) == .orderedAscending }
                return a.sourceOrder < b.sourceOrder
            }
        let grouped = Dictionary(grouping: list) { Calendar.current.startOfDay(for: $0.dateFetched) }
        return grouped.keys.sorted(by: >).map { ($0, grouped[$0] ?? []) }
    }

    var body: some View {
        NavigationStack {
            List {
                if let last = updater.lastUpdate {
                    Text("Ultimo aggiornamento libreria: \(last.formatted(date: .abbreviated, time: .shortened))")
                        .font(.caption.italic())
                        .foregroundStyle(.secondary)
                        .listRowSeparator(.hidden)
                }
                if !updater.errors.isEmpty {
                    Button("\(updater.errors.count) errori durante l'aggiornamento") { showErrors = true }
                        .font(.caption)
                        .foregroundStyle(.red)
                }
                ForEach(groups, id: \.0) { day, chapters in
                    Section(DateText.header(day)) {
                        ForEach(chapters) { chapter in
                            if let manga = chapter.manga {
                                HStack(spacing: 12) {
                                    NavigationLink { MangaDetailView(entity: manga) } label: {
                                        CoverImage(url: manga.thumbnailURL, headers: extensions.source(id: manga.sourceId)?.coverHeaders ?? [:])
                                            .frame(width: 48)
                                    }
                                    .buttonStyle(.plain)
                                    .frame(width: 48)
                                    Button {
                                        reader = ReaderTarget(mangaKey: manga.key, chapterKey: chapter.key)
                                    } label: {
                                        VStack(alignment: .leading, spacing: 3) {
                                            Text(manga.title).font(.subheadline.bold()).lineLimit(1)
                                            Text(chapter.name).font(.caption).lineLimit(1).foregroundStyle(.secondary)
                                        }
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                    .opacity(chapter.read ? 0.45 : 1)
                                    if chapter.isDownloaded {
                                        Image(systemName: "checkmark.circle.fill").foregroundStyle(.secondary)
                                    } else if downloads.isQueued(chapter) != nil {
                                        ProgressView()
                                    } else {
                                        Button { downloads.enqueue([chapter], manga: manga) } label: {
                                            Image(systemName: "arrow.down.circle").font(.title3)
                                        }
                                        .buttonStyle(.borderless)
                                        .foregroundStyle(.secondary)
                                    }
                                }
                                .swipeActions {
                                    Button(chapter.read ? "Non letto" : "Letto") {
                                        MangaRepository.setRead([chapter], read: !chapter.read)
                                        chapter.isNew = true
                                        try? context.save()
                                    }
                                    .tint(.accentColor)
                                }
                            }
                        }
                    }
                }
            }
            .listStyle(.plain)
            .overlay {
                if groups.isEmpty && !updater.isUpdating {
                    EmptyStateView(face: "Σ(ಠ_ಠ)", message: "Nessuna novità recente")
                }
            }
            .overlay(alignment: .bottom) {
                if updater.isUpdating { UpdateProgressBanner() }
            }
            .navigationTitle("Novità")
            .refreshable { await updater.update() }
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { unreadOnly.toggle() } label: {
                        Image(systemName: unreadOnly ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease")
                    }
                    Button { Task { await updater.update() } } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .disabled(updater.isUpdating)
                }
            }
            .fullScreenCover(item: $reader) { ReaderView(mangaKey: $0.mangaKey, chapterKey: $0.chapterKey) }
            .alert("Errori", isPresented: $showErrors) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(updater.errors.prefix(10).joined(separator: "\n"))
            }
        }
    }
}

struct HistoryView: View {
    @Environment(\.modelContext) private var context
    @EnvironmentObject private var extensions: ExtensionStore
    @Query(filter: #Predicate<ChapterEntity> { $0.lastReadAt != nil }) private var readChapters: [ChapterEntity]
    @State private var search = ""
    @State private var reader: ReaderTarget?
    @State private var confirmClear = false

    /// Come Mihon: una voce per manga, con l'ultimo capitolo letto.
    private var groups: [(Date, [ChapterEntity])] {
        var latest: [String: ChapterEntity] = [:]
        for c in readChapters {
            guard let key = c.manga?.key, let date = c.lastReadAt else { continue }
            if let current = latest[key], (current.lastReadAt ?? .distantPast) >= date { continue }
            latest[key] = c
        }
        var list = Array(latest.values)
        if !search.isEmpty { list = list.filter { $0.manga?.title.localizedCaseInsensitiveContains(search) == true } }
        list.sort { ($0.lastReadAt ?? .distantPast) > ($1.lastReadAt ?? .distantPast) }
        let grouped = Dictionary(grouping: list) { Calendar.current.startOfDay(for: $0.lastReadAt ?? .distantPast) }
        return grouped.keys.sorted(by: >).map { ($0, grouped[$0] ?? []) }
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(groups, id: \.0) { day, chapters in
                    Section(DateText.header(day)) {
                        ForEach(chapters) { chapter in
                            if let manga = chapter.manga {
                                HStack(spacing: 14) {
                                    NavigationLink { MangaDetailView(entity: manga) } label: {
                                        CoverImage(url: manga.thumbnailURL, headers: extensions.source(id: manga.sourceId)?.coverHeaders ?? [:])
                                            .frame(width: 56)
                                    }
                                    .buttonStyle(.plain)
                                    .frame(width: 56)
                                    Button {
                                        let target = chapter.read ? (manga.nextChapterToRead ?? chapter) : chapter
                                        reader = ReaderTarget(mangaKey: manga.key, chapterKey: target.key)
                                    } label: {
                                        VStack(alignment: .leading, spacing: 4) {
                                            Text(manga.title).font(.subheadline.bold()).lineLimit(2)
                                            Text("\(chapter.name) - \((chapter.lastReadAt ?? Date()).formatted(date: .omitted, time: .shortened))")
                                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                        }
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                    Button {
                                        for c in manga.chapters { c.lastReadAt = nil }
                                        try? context.save()
                                    } label: {
                                        Image(systemName: "trash")
                                    }
                                    .buttonStyle(.borderless)
                                    .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            .listStyle(.plain)
            .overlay {
                if groups.isEmpty { EmptyStateView(face: "(・_・;)", message: "Niente letto di recente") }
            }
            .navigationTitle("Cronologia")
            .searchable(text: $search, prompt: "Cerca")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { confirmClear = true } label: { Image(systemName: "trash") }
                        .disabled(readChapters.isEmpty)
                }
            }
            .confirmationDialog("Eliminare tutta la cronologia?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Elimina tutto", role: .destructive) {
                    for c in readChapters { c.lastReadAt = nil }
                    try? context.save()
                }
            }
            .fullScreenCover(item: $reader) { ReaderView(mangaKey: $0.mangaKey, chapterKey: $0.chapterKey) }
        }
    }
}
