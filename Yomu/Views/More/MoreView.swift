import SwiftUI
import SwiftData

struct MoreView: View {
    @AppStorage(Prefs.downloadedOnly) private var downloadedOnly = false
    @AppStorage(Prefs.incognito) private var incognito = false
    @EnvironmentObject private var downloads: DownloadManager

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Spacer()
                        Text("読む").font(.system(size: 54, weight: .heavy))
                        Spacer()
                    }
                    .listRowBackground(Color.clear)
                }
                Section {
                    Toggle(isOn: $downloadedOnly) {
                        SettingLabel("Solo scaricati", subtitle: "Filtra tutte le voci nella tua libreria", icon: "icloud.slash")
                    }
                    Toggle(isOn: $incognito) {
                        SettingLabel("Modalità incognito", subtitle: "Sospendi cronologia di lettura", icon: "eyeglasses")
                    }
                }
                Section {
                    NavigationLink { DownloadQueueView() } label: {
                        HStack {
                            SettingLabel("Coda download", icon: "arrow.down.to.line")
                            if !downloads.queue.isEmpty {
                                Spacer()
                                Text("\(downloads.queue.count)").foregroundStyle(.secondary)
                            }
                        }
                    }
                    NavigationLink { CategoriesView() } label: { SettingLabel("Categorie", icon: "tag") }
                    NavigationLink { StatisticsView() } label: { SettingLabel("Statistiche", icon: "chart.xyaxis.line") }
                    NavigationLink { DataStorageSettings() } label: { SettingLabel("Dati e archiviazione", icon: "externaldrive") }
                }
                Section {
                    NavigationLink { SettingsView() } label: { SettingLabel("Impostazioni", icon: "gearshape") }
                    NavigationLink { AboutView() } label: { SettingLabel("Info", icon: "info.circle") }
                    Link(destination: URL(string: "https://mihon.app/docs/guides/getting-started")!) {
                        SettingLabel("Aiuto", icon: "questionmark.circle")
                    }
                }
            }
            .navigationTitle("Altro")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(isPresented: $showDemoSettings) { SettingsView() }
        }
    }

    @State private var showDemoSettings = Demo.screen == "settings"
}

struct SettingLabel: View {
    let title: String
    var subtitle: String?
    let icon: String

    init(_ title: String, subtitle: String? = nil, icon: String) {
        self.title = title
        self.subtitle = subtitle
        self.icon = icon
    }

    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(Color.accentColor)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(.primary)
                if let subtitle {
                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

struct DownloadQueueView: View {
    @EnvironmentObject private var downloads: DownloadManager

    var body: some View {
        List {
            if downloads.blockedByNetwork {
                Label("In attesa di una rete Wi-Fi", systemImage: "wifi.exclamationmark").foregroundStyle(.orange)
            }
            ForEach(downloads.queue) { item in
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.mangaTitle).font(.subheadline.bold()).lineLimit(1)
                    Text(item.chapterName).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    if let error = item.error {
                        Text(error).font(.caption).foregroundStyle(.red)
                    } else {
                        ProgressView(value: item.progress)
                    }
                }
                .swipeActions {
                    Button("Annulla", role: .destructive) { downloads.cancel(item) }
                }
            }
        }
        .overlay {
            if downloads.queue.isEmpty { EmptyStateView(face: "(￣▽￣)", message: "Nessun download in coda") }
        }
        .navigationTitle("Coda download")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    downloads.paused.toggle()
                    downloads.startIfNeeded()
                } label: {
                    Image(systemName: downloads.paused ? "play.fill" : "pause.fill")
                }
                Menu {
                    Button("Riprova falliti", systemImage: "arrow.clockwise") { downloads.retryFailed() }
                    Button("Svuota coda", systemImage: "trash", role: .destructive) { downloads.clearQueue() }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
    }
}

struct CategoriesView: View {
    @Environment(\.modelContext) private var context
    @Query(sort: \CategoryEntity.order) private var categories: [CategoryEntity]
    @State private var newName = ""
    @State private var showAdd = false
    @State private var renaming: CategoryEntity?
    @State private var renameText = ""

    var body: some View {
        List {
            ForEach(categories) { category in
                Text(category.name)
                    .swipeActions {
                        Button("Elimina", role: .destructive) {
                            context.delete(category)
                            try? context.save()
                        }
                        Button("Rinomina") {
                            renameText = category.name
                            renaming = category
                        }
                    }
            }
            .onMove { from, to in
                var list = categories
                list.move(fromOffsets: from, toOffset: to)
                for (i, c) in list.enumerated() { c.order = i }
                try? context.save()
            }
        }
        .overlay {
            if categories.isEmpty { EmptyStateView(face: "(・・;)", message: "Non hai ancora categorie") }
        }
        .navigationTitle("Categorie")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) { EditButton() }
            ToolbarItem(placement: .topBarTrailing) {
                Button { newName = ""; showAdd = true } label: { Image(systemName: "plus") }
            }
        }
        .alert("Nuova categoria", isPresented: $showAdd) {
            TextField("Nome", text: $newName)
            Button("Annulla", role: .cancel) {}
            Button("Aggiungi") {
                let name = newName.trimmed
                guard !name.isEmpty else { return }
                context.insert(CategoryEntity(name: name, order: categories.count))
                try? context.save()
            }
        }
        .alert("Rinomina categoria", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Nome", text: $renameText)
            Button("Annulla", role: .cancel) { renaming = nil }
            Button("Salva") {
                renaming?.name = renameText.trimmed
                try? context.save()
                renaming = nil
            }
        }
    }
}

struct StatisticsView: View {
    @Query(filter: #Predicate<MangaEntity> { $0.inLibrary == true }) private var library: [MangaEntity]
    @Query(filter: #Predicate<ChapterEntity> { $0.read == true }) private var readChapters: [ChapterEntity]

    var body: some View {
        let totalChapters = library.reduce(0) { $0 + $1.chapters.count }
        let downloaded = library.reduce(0) { $0 + $1.downloadedCount }
        let completed = library.filter { $0.statusValue == .completed }.count
        let started = library.filter { $0.chapters.contains(where: \.read) }.count
        let finished = library.filter { !$0.chapters.isEmpty && $0.unreadCount == 0 }.count
        let sources = Set(library.map(\.sourceId)).count
        List {
            Section("Panoramica") {
                StatRow(label: "Manga in libreria", value: library.count)
                StatRow(label: "Completati (serie)", value: completed)
                StatRow(label: "Iniziati", value: started)
                StatRow(label: "Letti fino alla fine", value: finished)
                StatRow(label: "Fonti utilizzate", value: sources)
            }
            Section("Capitoli") {
                StatRow(label: "Totali in libreria", value: totalChapters)
                StatRow(label: "Letti (tutti i manga)", value: readChapters.count)
                StatRow(label: "Non letti in libreria", value: library.reduce(0) { $0 + $1.unreadCount })
                StatRow(label: "Scaricati", value: downloaded)
            }
        }
        .navigationTitle("Statistiche")
    }
}

struct StatRow: View {
    let label: String
    let value: Int
    var body: some View {
        HStack {
            Text(label)
            Spacer()
            Text("\(value)").font(.headline.monospacedDigit()).foregroundStyle(Color.accentColor)
        }
    }
}

struct AboutView: View {
    var body: some View {
        List {
            Section {
                VStack(spacing: 8) {
                    Text("読む").font(.system(size: 60, weight: .heavy))
                    Text("Yomu").font(.title.bold())
                    Text("Versione \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?")")
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding()
            }
            Section {
                Text("Lettore di manga per iPhone e iPad ispirato a Mihon. Legge i repository di estensioni in formato Mihon e usa motori Swift compatibili con i temi Madara, MangaThemesia, MangaWorld e PizzaReader.")
                    .font(.subheadline)
                Link("Mihon", destination: URL(string: "https://mihon.app")!)
                Link("Keiyoushi", destination: URL(string: "https://keiyoushi.github.io")!)
            }
            Section("Librerie") {
                Text("SwiftSoup — parser HTML (MIT)")
            }
        }
        .navigationTitle("Info")
    }
}
