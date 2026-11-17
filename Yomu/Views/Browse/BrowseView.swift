import SwiftUI
import SwiftData

struct BrowseView: View {
    @EnvironmentObject private var extensions: ExtensionStore
    @State private var tab = 0
    @State private var showGlobalSearch = false
    @State private var showFilter = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("", selection: $tab) {
                    Text("Fonti").tag(0)
                    Text(extensions.updatableCount > 0 ? "Estensioni (\(extensions.updatableCount))" : "Estensioni").tag(1)
                    Text("Migra").tag(2)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                .padding(.bottom, 6)

                switch tab {
                case 0: SourcesList()
                case 1: ExtensionsList()
                default: MigrateSourcesList()
                }
            }
            .navigationTitle("Sfoglia")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { showGlobalSearch = true } label: { Image(systemName: "magnifyingglass.circle") }
                    Button { showFilter = true } label: { Image(systemName: "line.3.horizontal.decrease") }
                }
            }
            .navigationDestination(isPresented: $showGlobalSearch) { GlobalSearchView() }
            .sheet(isPresented: $showFilter) { LanguageFilterSheet() }
        }
    }
}

// MARK: Fonti

struct SourcesList: View {
    @EnvironmentObject private var extensions: ExtensionStore

    var body: some View {
        let sources = extensions.installedSources
        List {
            if sources.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Nessuna fonte installata").font(.headline)
                    Text(extensions.repos.isEmpty
                         ? "Aggiungi un repository in Impostazioni → Sfoglia → Repository estensioni, poi installa le estensioni dalla scheda Estensioni."
                         : "Installa le estensioni dalla scheda Estensioni.")
                        .font(.subheadline).foregroundStyle(.secondary)
                    if extensions.repos.isEmpty {
                        NavigationLink("Gestisci repository") { RepositoriesView() }
                    }
                }
                .padding(.vertical)
            }
            if let lastId = extensions.lastUsedSource, let last = sources.first(where: { $0.id == lastId }) {
                Section("Ultimo usato") { SourceRow(source: last) }
            }
            let pinned = sources.filter { extensions.pinnedSources.contains($0.id) }
            if !pinned.isEmpty {
                Section("Fissate") { ForEach(pinned) { SourceRow(source: $0) } }
            }
            let byLang = Dictionary(grouping: sources, by: \.lang)
            ForEach(byLang.keys.sorted { ExtensionStore.languageName($0) < ExtensionStore.languageName($1) }, id: \.self) { lang in
                Section(ExtensionStore.languageName(lang)) {
                    ForEach(byLang[lang] ?? []) { SourceRow(source: $0) }
                }
            }
        }
        .listStyle(.plain)
    }
}

struct SourceRow: View {
    let source: SourceInfo
    @EnvironmentObject private var extensions: ExtensionStore

    var body: some View {
        HStack(spacing: 14) {
            NavigationLink {
                SourceBrowseView(sourceId: source.id, mode: .popular)
            } label: {
                HStack(spacing: 14) {
                    ExtensionIcon(url: extensions.extensionFor(sourceId: source.id)?.iconURL)
                    VStack(alignment: .leading) {
                        Text(source.name)
                        Text(ExtensionStore.languageName(source.lang)).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            NavigationLink {
                SourceBrowseView(sourceId: source.id, mode: .latest)
            } label: {
                Text("Ultimi").font(.subheadline.bold()).foregroundStyle(Color.accentColor)
            }
            .fixedSize()
            Button {
                extensions.togglePin(source.id)
            } label: {
                Image(systemName: extensions.pinnedSources.contains(source.id) ? "pin.fill" : "pin")
                    .foregroundStyle(extensions.pinnedSources.contains(source.id) ? Color.accentColor : .secondary)
            }
            .buttonStyle(.borderless)
        }
    }
}

struct ExtensionIcon: View {
    let url: String?
    var body: some View {
        RemoteImage(url: url)
            .frame(width: 40, height: 40)
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

// MARK: Estensioni

struct ExtensionsList: View {
    @EnvironmentObject private var extensions: ExtensionStore
    @AppStorage(Prefs.showNSFW) private var showNSFW = false
    @AppStorage(Prefs.showUnsupported) private var showUnsupported = true
    @State private var search = ""

    private var filtered: [RepoExtension] {
        let langs = extensions.enabledLanguages
        return extensions.available.filter { ext in
            (langs.isEmpty || langs.contains(ext.lang))
            && (showNSFW || !ext.isNSFW)
            && (showUnsupported || extensions.isSupported(ext))
            && (search.isEmpty || ext.name.localizedCaseInsensitiveContains(search))
        }
    }

    var body: some View {
        let list = filtered
        let installed = list.filter { extensions.isInstalled($0) }
        let notInstalled = list.filter { !extensions.isInstalled($0) }
        let byLang = Dictionary(grouping: notInstalled, by: \.lang)
        List {
            if extensions.repos.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Nessun repository").font(.headline)
                    Text("Aggiungi l'indirizzo di un repository di estensioni (es. Keiyoushi).").foregroundStyle(.secondary)
                    NavigationLink("Gestisci repository") { RepositoriesView() }
                }
                .padding(.vertical)
            }
            if let err = extensions.lastError {
                Text(err).font(.caption).foregroundStyle(.red)
            }
            if !installed.isEmpty {
                Section("Installate") {
                    ForEach(installed) { ExtensionRow(ext: $0) }
                }
            }
            ForEach(byLang.keys.sorted { ExtensionStore.languageName($0) < ExtensionStore.languageName($1) }, id: \.self) { lang in
                Section(ExtensionStore.languageName(lang)) {
                    ForEach((byLang[lang] ?? []).sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }) {
                        ExtensionRow(ext: $0)
                    }
                }
            }
        }
        .listStyle(.plain)
        .searchable(text: $search, prompt: "Cerca estensioni")
        .refreshable { await extensions.refresh() }
        .overlay { if extensions.isRefreshing && extensions.available.isEmpty { ProgressView() } }
    }
}

struct ExtensionRow: View {
    let ext: RepoExtension
    @EnvironmentObject private var extensions: ExtensionStore
    @State private var showInfo = false

    var body: some View {
        let supported = extensions.isSupported(ext)
        let installed = extensions.isInstalled(ext)
        HStack(spacing: 14) {
            ExtensionIcon(url: ext.iconURL)
                .opacity(supported ? 1 : 0.4)
            VStack(alignment: .leading, spacing: 2) {
                Text(ext.name).opacity(supported ? 1 : 0.5)
                HStack(spacing: 6) {
                    Text(ExtensionStore.languageName(ext.lang))
                    Text(ext.version)
                    if ext.isNSFW { Text("18+").foregroundStyle(.red) }
                    if let theme = extensions.theme(for: ext) {
                        Text(theme.engineName).foregroundStyle(Color.accentColor)
                    } else {
                        Text("Non supportata")
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            Spacer()
            if installed {
                if extensions.hasUpdate(ext) {
                    Button("Aggiorna") { extensions.install(ext) }.buttonStyle(.bordered)
                } else {
                    Button("Rimuovi") { extensions.uninstall(ext) }.buttonStyle(.bordered).tint(.secondary)
                }
            } else if supported {
                Button("Installa") { extensions.install(ext) }.buttonStyle(.bordered)
            } else {
                Button { showInfo = true } label: { Image(systemName: "info.circle") }
                    .buttonStyle(.borderless)
            }
        }
        .alert("Estensione non supportata", isPresented: $showInfo) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("\(ext.name) usa codice Android scritto apposta per quel sito, che non può girare su iOS. Yomu supporta le estensioni basate sui motori Madara, MangaThemesia, MangaWorld e PizzaReader.")
        }
    }
}

struct LanguageFilterSheet: View {
    @EnvironmentObject private var extensions: ExtensionStore
    @Environment(\.dismiss) private var dismiss
    @AppStorage(Prefs.showNSFW) private var showNSFW = false
    @AppStorage(Prefs.showUnsupported) private var showUnsupported = true

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Toggle("Mostra estensioni 18+", isOn: $showNSFW)
                    Toggle("Mostra estensioni non supportate", isOn: $showUnsupported)
                }
                Section("Lingue") {
                    ForEach(extensions.availableLanguages, id: \.self) { lang in
                        Toggle(ExtensionStore.languageName(lang), isOn: Binding(
                            get: { extensions.enabledLanguages.contains(lang) },
                            set: { on in
                                var set = extensions.enabledLanguages
                                if on { set.insert(lang) } else { set.remove(lang) }
                                extensions.enabledLanguages = set
                            }
                        ))
                    }
                }
            }
            .navigationTitle("Filtro")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fatto") { dismiss() } } }
        }
    }
}

// MARK: Repository

struct RepositoriesView: View {
    @EnvironmentObject private var extensions: ExtensionStore
    @State private var showAdd = false
    @State private var newURL = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
    @State private var adding = false
    @State private var error: String?

    var body: some View {
        List {
            Section {
                ForEach(extensions.repos) { repo in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(repo.name)
                        Text(repo.url).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    }
                    .swipeActions {
                        Button("Elimina", role: .destructive) { extensions.removeRepo(repo) }
                    }
                }
            } footer: {
                Text("Yomu legge gli indici in formato index.pb (Mihon 0.20+) e index.min.json. Scorri a sinistra per eliminare un repository.")
            }
            if let error {
                Text(error).foregroundStyle(.red).font(.caption)
            }
        }
        .navigationTitle("Repository estensioni")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showAdd = true } label: { Image(systemName: "plus") }
            }
        }
        .overlay { if adding { ProgressView() } }
        .alert("Aggiungi repository", isPresented: $showAdd) {
            TextField("URL", text: $newURL)
                .textInputAutocapitalization(.never)
                .keyboardType(.URL)
            Button("Annulla", role: .cancel) {}
            Button("Aggiungi") {
                let url = newURL
                adding = true
                Task {
                    do {
                        try await extensions.addRepo(url)
                        error = nil
                    } catch {
                        self.error = error.localizedDescription
                    }
                    adding = false
                }
            }
        } message: {
            Text("Incolla l'indirizzo dell'indice del repository.")
        }
    }
}

// MARK: Migra

struct MigrateSourcesList: View {
    @Query(filter: #Predicate<MangaEntity> { $0.inLibrary == true }) private var mangas: [MangaEntity]

    var body: some View {
        let bySource = Dictionary(grouping: mangas, by: \.sourceId)
        List {
            if mangas.isEmpty {
                Text("Nessun manga in libreria da migrare.").foregroundStyle(.secondary)
            }
            ForEach(bySource.keys.sorted(), id: \.self) { id in
                let list = bySource[id] ?? []
                NavigationLink {
                    MigrateMangaList(mangas: list.sorted { $0.title < $1.title })
                } label: {
                    HStack {
                        Text(list.first?.sourceName ?? id)
                        Spacer()
                        Text("\(list.count)").foregroundStyle(.secondary)
                    }
                }
            }
        }
        .listStyle(.plain)
    }
}

struct MigrateMangaList: View {
    let mangas: [MangaEntity]

    var body: some View {
        List(mangas) { manga in
            NavigationLink {
                MigrationSearchView(manga: manga)
            } label: {
                LibraryListRow(manga: manga)
            }
        }
        .listStyle(.plain)
        .navigationTitle("Scegli un manga")
    }
}

struct MigrationSearchView: View {
    let manga: MangaEntity
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var extensions: ExtensionStore
    @State private var query = ""
    @State private var results: [String: [SManga]] = [:]
    @State private var searching = false
    @State private var pending: (SourceInfo, SManga)?
    @State private var migrating = false
    @State private var error: String?

    var body: some View {
        List {
            if let error { Text(error).foregroundStyle(.red) }
            ForEach(extensions.installedSources.filter { $0.id != manga.sourceId }) { source in
                if let list = results[source.id], !list.isEmpty {
                    Section(source.name) {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(alignment: .top, spacing: 10) {
                                ForEach(list) { m in
                                    Button { pending = (source, m) } label: {
                                        VStack(alignment: .leading) {
                                            CoverImage(url: m.thumbnailURL, headers: extensions.source(id: source.id)?.coverHeaders ?? [:])
                                                .frame(width: 100)
                                            Text(m.title).font(.caption).lineLimit(2).frame(width: 100, alignment: .leading)
                                        }
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    }
                }
            }
        }
        .overlay { if searching || migrating { ProgressView() } }
        .navigationTitle("Migra \(manga.title)")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query)
        .onSubmit(of: .search) { Task { await runSearch() } }
        .task {
            if query.isEmpty { query = manga.title; await runSearch() }
        }
        .confirmationDialog("Migrare a \(pending?.0.name ?? "")?", isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } }), titleVisibility: .visible) {
            Button("Migra (rimuovi l'originale)") { migrate(removeOld: true) }
            Button("Copia (tieni l'originale fuori dalla libreria)") { migrate(removeOld: false) }
        } message: {
            Text("I capitoli letti, i segnalibri e le categorie verranno trasferiti in base al numero di capitolo.")
        }
    }

    private func runSearch() async {
        searching = true
        results = [:]
        let sources = extensions.installedSources.filter { $0.id != manga.sourceId }.compactMap { extensions.source(id: $0.id) }
        let q = query
        await withTaskGroup(of: (String, [SManga]).self) { group in
            for source in sources {
                group.addTask {
                    let list = (try? await source.search(query: q, page: 1).mangas) ?? []
                    return (source.id, Array(list.prefix(10)))
                }
            }
            for await (id, list) in group { results[id] = list }
        }
        searching = false
    }

    private func migrate(removeOld: Bool) {
        guard let choice = pending, let source = extensions.source(id: choice.0.id) else { return }
        let target = choice.1
        migrating = true
        Task {
            do {
                _ = try await MangaRepository.migrate(manga, to: target, source: source, in: context, removeOld: removeOld)
                migrating = false
                dismiss()
            } catch {
                self.error = error.localizedDescription
                migrating = false
            }
        }
    }
}
