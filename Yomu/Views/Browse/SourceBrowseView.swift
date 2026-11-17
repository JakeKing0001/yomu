import SwiftUI
import SwiftData

enum BrowseMode: Int, Hashable {
    case popular, latest
}

struct SourceBrowseView: View {
    let sourceId: String
    @State var mode: BrowseMode

    @EnvironmentObject private var extensions: ExtensionStore
    @Environment(\.modelContext) private var context
    @Query(filter: #Predicate<MangaEntity> { $0.inLibrary == true }) private var library: [MangaEntity]

    @State private var items: [SManga] = []
    @State private var page = 1
    @State private var hasNext = true
    @State private var loading = false
    @State private var error: Error?
    @State private var query = ""
    @State private var activeQuery = ""
    @State private var webURL: URL?
    @State private var generation = 0

    private var source: MangaSource? { extensions.source(id: sourceId) }

    var body: some View {
        ScrollView {
            if let error, items.isEmpty {
                ErrorBanner(error: error) { reload() } openWeb: { webURL = $0 }
                    .padding()
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 105), spacing: 10)], spacing: 10) {
                ForEach(items) { manga in
                    NavigationLink {
                        MangaDetailView(sourceId: sourceId, manga: manga)
                    } label: {
                        BrowseCell(manga: manga, headers: source?.coverHeaders ?? [:],
                                   inLibrary: library.contains { $0.sourceId == sourceId && $0.url == manga.url })
                    }
                    .buttonStyle(.plain)
                    .onAppear {
                        if manga.id == items.last?.id { Task { await loadMore() } }
                    }
                }
            }
            .padding(10)
            if loading {
                ProgressView().padding()
            } else if !items.isEmpty && !hasNext {
                Text("Fine dei risultati").font(.caption).foregroundStyle(.secondary).padding()
            } else if items.isEmpty && error == nil {
                Text("Nessun risultato").foregroundStyle(.secondary).padding(.top, 60)
            }
        }
        .navigationTitle(source?.name ?? "Fonte")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, prompt: "Cerca in \(source?.name ?? "fonte")")
        .onSubmit(of: .search) {
            activeQuery = query
            reload()
        }
        .onChange(of: query) { _, value in
            if value.isEmpty && !activeQuery.isEmpty {
                activeQuery = ""
                reload()
            }
        }
        .toolbar {
            ToolbarItem(placement: .principal) {
                Picker("", selection: $mode) {
                    Text("Popolari").tag(BrowseMode.popular)
                    Text("Ultimi").tag(BrowseMode.latest)
                }
                .pickerStyle(.segmented)
                .frame(width: 200)
                .disabled(!activeQuery.isEmpty)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    if let s = source { webURL = URL(string: s.baseURL) }
                } label: { Image(systemName: "globe") }
            }
        }
        .onChange(of: mode) { _, _ in reload() }
        .task {
            extensions.markUsed(sourceId)
            if items.isEmpty { await loadMore() }
        }
        .refreshable { reload() }
        .sheet(item: Binding(get: { webURL.map { IdentifiedURL(url: $0) } }, set: { webURL = $0?.url }), onDismiss: reload) { item in
            WebViewSheet(url: item.url)
        }
    }

    private func reload() {
        generation += 1
        items = []
        page = 1
        hasNext = true
        error = nil
        loading = false
        Task { await loadMore() }
    }

    private func loadMore() async {
        guard let source else {
            error = SourceError.sourceUnavailable
            return
        }
        guard !loading, hasNext else { return }
        loading = true
        let current = generation
        do {
            let result: MangasPage
            if !activeQuery.isEmpty {
                result = try await source.search(query: activeQuery, page: page)
            } else if mode == .latest {
                result = try await source.latest(page: page)
            } else {
                result = try await source.popular(page: page)
            }
            guard current == generation else { return }
            let known = Set(items.map(\.url))
            items += result.mangas.filter { !known.contains($0.url) }
            hasNext = result.hasNextPage && !result.mangas.isEmpty
            page += 1
            error = nil
        } catch {
            guard current == generation else { return }
            self.error = error
            hasNext = false
        }
        loading = false
    }
}

struct BrowseCell: View {
    let manga: SManga
    let headers: [String: String]
    var inLibrary = false

    var body: some View {
        CoverImage(url: manga.thumbnailURL, headers: headers)
            .overlay(alignment: .bottomLeading) {
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
            .overlay(alignment: .topLeading) {
                if inLibrary {
                    Text("In libreria")
                        .font(.caption2.bold())
                        .padding(.horizontal, 6).padding(.vertical, 3)
                        .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 6))
                        .foregroundStyle(.black)
                        .padding(5)
                }
            }
            .opacity(inLibrary ? 0.6 : 1)
    }
}

struct GlobalSearchView: View {
    @EnvironmentObject private var extensions: ExtensionStore
    @State private var query = ""
    @State private var results: [String: Result<[SManga], Error>] = [:]
    @State private var running = false
    @State private var pinnedOnly = false

    private var sources: [SourceInfo] {
        let all = extensions.installedSources
        return pinnedOnly ? all.filter { extensions.pinnedSources.contains($0.id) } : all
    }

    var body: some View {
        List {
            Toggle("Solo fonti fissate", isOn: $pinnedOnly)
            ForEach(sources) { source in
                if let result = results[source.id] {
                    Section {
                        switch result {
                        case .success(let list) where list.isEmpty:
                            Text("Nessun risultato").font(.caption).foregroundStyle(.secondary)
                        case .success(let list):
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(alignment: .top, spacing: 10) {
                                    ForEach(list) { manga in
                                        NavigationLink {
                                            MangaDetailView(sourceId: source.id, manga: manga)
                                        } label: {
                                            BrowseCell(manga: manga, headers: extensions.source(id: source.id)?.coverHeaders ?? [:])
                                                .frame(width: 105)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                            }
                        case .failure(let error):
                            Text(error.localizedDescription).font(.caption).foregroundStyle(.red)
                        }
                    } header: {
                        Text("\(source.name) • \(ExtensionStore.languageName(source.lang))")
                    }
                } else if running {
                    Section(source.name) { ProgressView() }
                }
            }
        }
        .navigationTitle("Ricerca globale")
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Cerca in tutte le fonti")
        .onSubmit(of: .search) { Task { await run() } }
    }

    private func run() async {
        guard !query.isEmpty else { return }
        running = true
        results = [:]
        let q = query
        let list = sources.compactMap { extensions.source(id: $0.id) }
        await withTaskGroup(of: (String, Result<[SManga], Error>).self) { group in
            for source in list {
                group.addTask {
                    do {
                        let page = try await source.search(query: q, page: 1)
                        return (source.id, .success(page.mangas))
                    } catch {
                        return (source.id, .failure(error))
                    }
                }
            }
            for await (id, result) in group { results[id] = result }
        }
        running = false
    }
}
