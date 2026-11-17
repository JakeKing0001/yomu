import SwiftUI
import SwiftData

enum LibrarySort: Int, CaseIterable {
    case alphabetical, lastRead, lastUpdate, unread, totalChapters, dateAdded

    var label: String {
        switch self {
        case .alphabetical: return "Alfabetico"
        case .lastRead: return "Ultima lettura"
        case .lastUpdate: return "Ultimo aggiornamento"
        case .unread: return "Non letti"
        case .totalChapters: return "Capitoli totali"
        case .dateAdded: return "Data di aggiunta"
        }
    }
}

struct LibraryView: View {
    static let defaultCategoryID = "__default__"

    @Environment(\.modelContext) private var context
    @EnvironmentObject private var updater: LibraryUpdater
    @EnvironmentObject private var extensions: ExtensionStore
    @Query(filter: #Predicate<MangaEntity> { $0.inLibrary == true }) private var mangas: [MangaEntity]
    @Query(sort: \CategoryEntity.order) private var categories: [CategoryEntity]

    @AppStorage(Prefs.librarySort) private var sortRaw = 0
    @AppStorage(Prefs.librarySortAscending) private var ascending = true
    @AppStorage(Prefs.libraryFilterUnread) private var filterUnread = false
    @AppStorage(Prefs.libraryFilterCompleted) private var filterCompleted = false
    @AppStorage(Prefs.downloadedOnly) private var downloadedOnly = false
    @AppStorage(Prefs.libraryShowTabs) private var showTabs = true

    @State private var selected = 0
    @State private var search = ""
    @State private var showOptions = false
    @State private var editing: MangaEntity?
    @State private var demoManga: MangaEntity?
    @State private var demoReader: ReaderTarget?

    private struct Tab: Identifiable, Hashable { let id: String; let name: String }

    private var tabs: [Tab] {
        var result: [Tab] = []
        let validIDs = Set(categories.map { $0.id })
        let hasDefault = mangas.contains { manga in !manga.categoryIDs.contains(where: { validIDs.contains($0) }) }
        if categories.isEmpty || hasDefault { result.append(Tab(id: Self.defaultCategoryID, name: "Predefinita")) }
        result += categories.map { Tab(id: $0.id, name: $0.name) }
        return result
    }

    private func items(for tab: Tab) -> [MangaEntity] {
        let validIDs = Set(categories.map(\.id))
        var list = mangas.filter { m in
            let ids = m.categoryIDs.filter(validIDs.contains)
            return tab.id == Self.defaultCategoryID ? ids.isEmpty : ids.contains(tab.id)
        }
        if !search.isEmpty {
            list = list.filter { $0.title.localizedCaseInsensitiveContains(search) || ($0.author ?? "").localizedCaseInsensitiveContains(search) }
        }
        if filterUnread { list = list.filter { $0.unreadCount > 0 } }
        if filterCompleted { list = list.filter { $0.statusValue == .completed } }
        if downloadedOnly { list = list.filter { $0.downloadedCount > 0 } }
        let sort = LibrarySort(rawValue: sortRaw) ?? .alphabetical
        list.sort { a, b in
            let ordered: Bool
            switch sort {
            case .alphabetical: ordered = a.title.localizedCaseInsensitiveCompare(b.title) == .orderedAscending
            case .lastRead: ordered = (a.lastReadAt ?? .distantPast) < (b.lastReadAt ?? .distantPast)
            case .lastUpdate: ordered = (a.lastUpdate ?? .distantPast) < (b.lastUpdate ?? .distantPast)
            case .unread: ordered = a.unreadCount < b.unreadCount
            case .totalChapters: ordered = a.chapters.count < b.chapters.count
            case .dateAdded: ordered = a.dateAdded < b.dateAdded
            }
            return ascending ? ordered : !ordered
        }
        return list
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                let tabs = self.tabs
                if tabs.count > 1 && showTabs {
                    CategoryTabBar(names: tabs.map(\.name), selected: $selected)
                }
                if mangas.isEmpty {
                    EmptyStateView(face: "(・o・;)", message: "La tua libreria è vuota.\nAggiungi manga da Sfoglia.")
                } else {
                    TabView(selection: $selected) {
                        ForEach(Array(tabs.enumerated()), id: \.element.id) { index, tab in
                            LibraryGrid(mangas: items(for: tab), onEditCategories: { editing = $0 })
                                .tag(index)
                        }
                    }
                    .tabViewStyle(.page(indexDisplayMode: .never))
                }
            }
            .navigationTitle("Libreria")
            .searchable(text: $search, prompt: "Cerca nella libreria")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { showOptions = true } label: {
                        Image(systemName: "line.3.horizontal.decrease")
                    }
                    Menu {
                        Button("Aggiorna libreria", systemImage: "arrow.clockwise") {
                            Task { await updater.update() }
                        }
                        if tabs.indices.contains(selected) {
                            Button("Aggiorna categoria", systemImage: "arrow.clockwise.circle") {
                                let id = tabs[selected].id
                                Task { await updater.update(categoryID: id) }
                            }
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .overlay(alignment: .bottom) {
                if updater.isUpdating {
                    UpdateProgressBanner()
                }
            }
            .refreshable {
                let id = tabs.indices.contains(selected) ? tabs[selected].id : nil
                await updater.update(categoryID: id)
            }
            .sheet(isPresented: $showOptions) {
                LibraryOptionsSheet().presentationDetents([.medium, .large])
            }
            .sheet(item: $editing) { manga in
                CategoryPickerSheet(manga: manga)
            }
            .onChange(of: tabs.count) { _, count in
                if selected >= count { selected = max(0, count - 1) }
            }
            .navigationDestination(item: $demoManga) { MangaDetailView(entity: $0) }
            .fullScreenCover(item: $demoReader) { ReaderView(mangaKey: $0.mangaKey, chapterKey: $0.chapterKey) }
            .task {
                guard !Demo.screen.isEmpty else { return }
                try? await Task.sleep(nanoseconds: 500_000_000)
                let candidate = mangas
                    .filter { !$0.chapters.isEmpty }
                    .sorted { $0.chapters.filter(\.read).count > $1.chapters.filter(\.read).count }
                    .first
                guard let manga = candidate else { return }
                if Demo.screen == "detail" {
                    demoManga = manga
                } else if Demo.screen == "reader", let chapter = manga.nextChapterToRead ?? manga.sortedChapters.last {
                    demoReader = ReaderTarget(mangaKey: manga.key, chapterKey: chapter.key)
                }
            }
        }
    }
}

struct UpdateProgressBanner: View {
    @EnvironmentObject private var updater: LibraryUpdater

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Aggiornamento libreria \(updater.progress.done)/\(updater.progress.total)")
                .font(.subheadline.bold())
            if let t = updater.currentTitle { Text(t).font(.caption).lineLimit(1).foregroundStyle(.secondary) }
            ProgressView(value: Double(updater.progress.done), total: Double(max(1, updater.progress.total)))
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
        .padding()
    }
}

struct CategoryTabBar: View {
    let names: [String]
    @Binding var selected: Int

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 22) {
                    ForEach(Array(names.enumerated()), id: \.offset) { index, name in
                        Button {
                            withAnimation { selected = index }
                        } label: {
                            VStack(spacing: 8) {
                                Text(name)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(selected == index ? Color.accentColor : .primary)
                                Capsule()
                                    .fill(selected == index ? Color.accentColor : .clear)
                                    .frame(height: 3)
                            }
                            .fixedSize()
                        }
                        .buttonStyle(.plain)
                        .id(index)
                    }
                }
                .padding(.horizontal)
                .padding(.top, 4)
            }
            .onChange(of: selected) { _, value in withAnimation { proxy.scrollTo(value, anchor: .center) } }
        }
        .overlay(alignment: .bottom) { Divider() }
    }
}

struct LibraryGrid: View {
    let mangas: [MangaEntity]
    var onEditCategories: (MangaEntity) -> Void

    @Environment(\.modelContext) private var context
    @AppStorage(Prefs.libraryDisplay) private var display = 0
    @AppStorage(Prefs.libraryColumnsPortrait) private var columnsPortrait = 0
    @AppStorage(Prefs.libraryColumnsLandscape) private var columnsLandscape = 0

    var body: some View {
        GeometryReader { geo in
            ScrollView {
                if mangas.isEmpty {
                    Text("Nessun manga in questa categoria")
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 80)
                } else if display == 2 {
                    LazyVStack(spacing: 0) {
                        ForEach(mangas) { manga in
                            NavigationLink { MangaDetailView(entity: manga) } label: {
                                LibraryListRow(manga: manga)
                            }
                            .buttonStyle(.plain)
                            .contextMenu { menu(manga) }
                        }
                    }
                } else {
                    LazyVGrid(columns: columns(width: geo.size.width, landscape: geo.size.width > geo.size.height), spacing: 10) {
                        ForEach(mangas) { manga in
                            NavigationLink { MangaDetailView(entity: manga) } label: {
                                LibraryCell(manga: manga, titleBelow: display == 1)
                            }
                            .buttonStyle(.plain)
                            .contextMenu { menu(manga) }
                        }
                    }
                    .padding(10)
                }
            }
        }
    }

    private func columns(width: CGFloat, landscape: Bool) -> [GridItem] {
        let count = landscape ? columnsLandscape : columnsPortrait
        if count > 0 { return Array(repeating: GridItem(.flexible(), spacing: 10), count: count) }
        let minimum: CGFloat = width > 700 ? 140 : 105
        return [GridItem(.adaptive(minimum: minimum), spacing: 10)]
    }

    @ViewBuilder
    private func menu(_ manga: MangaEntity) -> some View {
        Button("Modifica categorie", systemImage: "tag") { onEditCategories(manga) }
        Button("Segna tutto come letto", systemImage: "checkmark.circle") {
            MangaRepository.setRead(manga.chapters, read: true)
            try? context.save()
        }
        Button("Rimuovi dalla libreria", systemImage: "trash", role: .destructive) {
            manga.inLibrary = false
            try? context.save()
        }
    }
}

struct LibraryCell: View {
    let manga: MangaEntity
    var titleBelow = false
    @EnvironmentObject private var extensions: ExtensionStore
    @AppStorage(Prefs.libraryShowUnreadBadge) private var showUnread = true
    @AppStorage(Prefs.libraryShowDownloadBadge) private var showDownloads = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            CoverImage(url: manga.thumbnailURL, headers: extensions.source(id: manga.sourceId)?.coverHeaders ?? [:])
                .overlay(alignment: .bottomLeading) {
                    if !titleBelow {
                        ZStack(alignment: .bottomLeading) {
                            LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .center, endPoint: .bottom)
                            Text(manga.title)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.white)
                                .lineLimit(2)
                                .padding(6)
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    }
                }
                .overlay(alignment: .topLeading) {
                    HStack(spacing: 0) {
                        if showDownloads && manga.downloadedCount > 0 {
                            Badge(text: "\(manga.downloadedCount)", color: .orange)
                        }
                        if showUnread && manga.unreadCount > 0 {
                            Badge(text: "\(manga.unreadCount)", color: .accentColor)
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                    .padding(5)
                }
            if titleBelow {
                Text(manga.title).font(.caption.weight(.medium)).lineLimit(2)
            }
        }
    }
}

struct Badge: View {
    let text: String
    let color: Color

    var body: some View {
        Text(text)
            .font(.caption2.bold())
            .foregroundStyle(.black)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(color)
    }
}

struct LibraryListRow: View {
    let manga: MangaEntity
    @EnvironmentObject private var extensions: ExtensionStore

    var body: some View {
        HStack(spacing: 12) {
            CoverImage(url: manga.thumbnailURL, headers: extensions.source(id: manga.sourceId)?.coverHeaders ?? [:])
                .frame(width: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(manga.title).lineLimit(2)
                Text(manga.sourceName).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if manga.unreadCount > 0 {
                Badge(text: "\(manga.unreadCount)", color: .accentColor).clipShape(Capsule())
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 6)
        .contentShape(Rectangle())
    }
}

struct LibraryOptionsSheet: View {
    @AppStorage(Prefs.librarySort) private var sortRaw = 0
    @AppStorage(Prefs.librarySortAscending) private var ascending = true
    @AppStorage(Prefs.libraryFilterUnread) private var filterUnread = false
    @AppStorage(Prefs.libraryFilterCompleted) private var filterCompleted = false
    @AppStorage(Prefs.downloadedOnly) private var downloadedOnly = false
    @AppStorage(Prefs.libraryDisplay) private var display = 0
    @AppStorage(Prefs.libraryShowUnreadBadge) private var showUnread = true
    @AppStorage(Prefs.libraryShowDownloadBadge) private var showDownloads = false
    @AppStorage(Prefs.libraryShowTabs) private var showTabs = true
    @State private var page = 0

    var body: some View {
        NavigationStack {
            Form {
                Picker("", selection: $page) {
                    Text("Filtro").tag(0)
                    Text("Ordina").tag(1)
                    Text("Mostra").tag(2)
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)

                switch page {
                case 0:
                    Toggle("Scaricati", isOn: $downloadedOnly)
                    Toggle("Non letti", isOn: $filterUnread)
                    Toggle("Completati", isOn: $filterCompleted)
                case 1:
                    ForEach(LibrarySort.allCases, id: \.rawValue) { sort in
                        Button {
                            if sortRaw == sort.rawValue { ascending.toggle() } else { sortRaw = sort.rawValue }
                        } label: {
                            HStack {
                                Text(sort.label).foregroundStyle(.primary)
                                Spacer()
                                if sortRaw == sort.rawValue {
                                    Image(systemName: ascending ? "arrow.up" : "arrow.down").foregroundStyle(Color.accentColor)
                                }
                            }
                        }
                    }
                default:
                    Picker("Visualizzazione", selection: $display) {
                        Text("Griglia compatta").tag(0)
                        Text("Griglia comoda").tag(1)
                        Text("Lista").tag(2)
                    }
                    .pickerStyle(.inline)
                    Section("Badge") {
                        Toggle("Capitoli non letti", isOn: $showUnread)
                        Toggle("Capitoli scaricati", isOn: $showDownloads)
                    }
                    Section("Schede") {
                        Toggle("Mostra schede categorie", isOn: $showTabs)
                    }
                }
            }
            .navigationTitle("Opzioni libreria")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

struct CategoryPickerSheet: View {
    let manga: MangaEntity
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @Query(sort: \CategoryEntity.order) private var categories: [CategoryEntity]

    var body: some View {
        NavigationStack {
            List {
                if categories.isEmpty {
                    Text("Nessuna categoria. Creane una da Altro → Categorie.").foregroundStyle(.secondary)
                }
                ForEach(categories) { category in
                    Button {
                        if let i = manga.categoryIDs.firstIndex(of: category.id) {
                            manga.categoryIDs.remove(at: i)
                        } else {
                            manga.categoryIDs.append(category.id)
                        }
                        try? context.save()
                    } label: {
                        HStack {
                            Text(category.name).foregroundStyle(.primary)
                            Spacer()
                            if manga.categoryIDs.contains(category.id) {
                                Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Categorie")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fatto") { dismiss() } } }
        }
        .presentationDetents([.medium])
    }
}

struct EmptyStateView: View {
    let face: String
    let message: String

    var body: some View {
        VStack(spacing: 16) {
            Text(face).font(.system(size: 48, weight: .light))
            Text(message).multilineTextAlignment(.center).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }
}
