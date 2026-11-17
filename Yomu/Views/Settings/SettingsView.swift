import SwiftUI
import SwiftData
import UniformTypeIdentifiers

struct SettingsView: View {
    var body: some View {
        List {
            NavigationLink { AppearanceSettings() } label: {
                SettingLabel("Aspetto", subtitle: "Tema, formato data e ora", icon: "paintpalette")
            }
            NavigationLink { LibrarySettings() } label: {
                SettingLabel("Libreria", subtitle: "Categorie, aggiornamenti globali, colonne", icon: "books.vertical")
            }
            NavigationLink { ReaderSettings() } label: {
                SettingLabel("Lettore", subtitle: "Modalità di lettura, aspetto, navigazione", icon: "book")
            }
            NavigationLink { DownloadSettings() } label: {
                SettingLabel("Download", subtitle: "Download automatico, eliminazione dopo la lettura", icon: "arrow.down.to.line")
            }
            NavigationLink { TrackingSettings() } label: {
                SettingLabel("Tracciamento", subtitle: "Sincronizzazione avanzamenti", icon: "arrow.triangle.2.circlepath")
            }
            NavigationLink { BrowseSettings() } label: {
                SettingLabel("Sfoglia", subtitle: "Fonti, estensioni, ricerca globale", icon: "safari")
            }
            NavigationLink { DataStorageSettings() } label: {
                SettingLabel("Dati e archiviazione", subtitle: "Backup manuali, spazio di archiviazione", icon: "externaldrive")
            }
            NavigationLink { SecuritySettings() } label: {
                SettingLabel("Sicurezza e privacy", subtitle: "Blocco app, schermo protetto", icon: "lock.shield")
            }
            NavigationLink { AdvancedSettings() } label: {
                SettingLabel("Avanzate", subtitle: "Cookie, User-Agent, cache", icon: "chevron.left.forwardslash.chevron.right")
            }
            NavigationLink { AboutView() } label: {
                SettingLabel("Info", icon: "info.circle")
            }
        }
        .navigationTitle("Impostazioni")
    }
}

struct AppearanceSettings: View {
    @AppStorage(Prefs.theme) private var theme = 0
    @AppStorage(Prefs.accent) private var accent = 0
    @AppStorage(Prefs.dateFormat) private var dateFormat = 0

    var body: some View {
        Form {
            Section("Tema") {
                Picker("Modalità", selection: $theme) {
                    Text("Sistema").tag(0)
                    Text("Chiaro").tag(1)
                    Text("Scuro").tag(2)
                }
                .pickerStyle(.segmented)
                Picker("Colore", selection: $accent) {
                    ForEach(AccentChoice.allCases, id: \.rawValue) { c in
                        HStack {
                            Circle().fill(c.color).frame(width: 16, height: 16)
                            Text(c.label)
                        }
                        .tag(c.rawValue)
                    }
                }
            }
            Section("Data") {
                Picker("Formato data", selection: $dateFormat) {
                    Text("Relativo (Oggi, Ieri…)").tag(0)
                    Text(Date().formatted(date: .numeric, time: .omitted)).tag(1)
                    Text(Date().formatted(date: .long, time: .omitted)).tag(2)
                }
            }
        }
        .navigationTitle("Aspetto")
    }
}

struct LibrarySettings: View {
    @Query(sort: \CategoryEntity.order) private var categories: [CategoryEntity]
    @AppStorage(Prefs.libraryColumnsPortrait) private var portrait = 0
    @AppStorage(Prefs.libraryColumnsLandscape) private var landscape = 0
    @AppStorage(Prefs.defaultCategory) private var defaultCategory = ""
    @AppStorage(Prefs.updateOnlyOngoing) private var onlyOngoing = false
    @AppStorage(Prefs.updateSkipRead) private var skipUnread = false
    @AppStorage(Prefs.libraryShowTabs) private var showTabs = true

    var body: some View {
        Form {
            Section("Visualizzazione") {
                Stepper("Colonne in verticale: \(portrait == 0 ? "auto" : "\(portrait)")", value: $portrait, in: 0...10)
                Stepper("Colonne in orizzontale: \(landscape == 0 ? "auto" : "\(landscape)")", value: $landscape, in: 0...14)
                Toggle("Mostra schede categorie", isOn: $showTabs)
            }
            Section("Categorie") {
                NavigationLink("Modifica categorie") { CategoriesView() }
                Picker("Categoria predefinita", selection: $defaultCategory) {
                    Text("Predefinita").tag("")
                    ForEach(categories) { Text($0.name).tag($0.id) }
                }
            }
            Section {
                Toggle("Salta i manga completati", isOn: $onlyOngoing)
                Toggle("Salta i manga con capitoli non letti", isOn: $skipUnread)
            } header: {
                Text("Aggiornamento globale")
            } footer: {
                Text("L'aggiornamento si avvia da Libreria o Novità (trascina verso il basso). iOS non permette aggiornamenti in background affidabili per app installate fuori dall'App Store.")
            }
        }
        .navigationTitle("Libreria")
    }
}

struct ReaderSettings: View {
    @AppStorage(Prefs.readerMode) private var mode = ReaderMode.rightToLeft.rawValue
    @AppStorage(Prefs.readerKeepScreenOn) private var keepScreenOn = true
    @AppStorage(Prefs.readerShowPageNumber) private var pageNumber = true
    @AppStorage(Prefs.readerBackground) private var background = 0
    @AppStorage(Prefs.readerTapZones) private var tapZones = true
    @AppStorage(Prefs.readerInvertTap) private var invertTap = false
    @AppStorage(Prefs.readerWebtoonGap) private var gap = 0.0
    @AppStorage(Prefs.readerPreload) private var preload = 4

    var body: some View {
        Form {
            Section("Modalità di lettura") {
                Picker("Predefinita", selection: $mode) {
                    ForEach(ReaderMode.allCases, id: \.rawValue) { Text($0.label).tag($0.rawValue) }
                }
                .pickerStyle(.inline)
            }
            Section("Aspetto") {
                Picker("Colore di sfondo", selection: $background) {
                    Text("Nero").tag(0)
                    Text("Bianco").tag(1)
                    Text("Grigio").tag(2)
                }
                Toggle("Mostra numero di pagina", isOn: $pageNumber)
                Toggle("Mantieni lo schermo acceso", isOn: $keepScreenOn)
            }
            Section("Navigazione") {
                Toggle("Zone di tocco (sinistra/destra cambiano pagina)", isOn: $tapZones)
                Toggle("Inverti zone di tocco", isOn: $invertTap)
                Stepper("Pagine precaricate: \(preload)", value: $preload, in: 1...10)
            }
            Section("Webtoon") {
                VStack(alignment: .leading) {
                    Text("Spazio tra le pagine: \(Int(gap)) pt")
                    Slider(value: $gap, in: 0...40, step: 2)
                }
            }
        }
        .navigationTitle("Lettore")
    }
}

struct DownloadSettings: View {
    @AppStorage(Prefs.downloadWiFiOnly) private var wifiOnly = false
    @AppStorage(Prefs.autoDownloadNew) private var autoNew = false
    @AppStorage(Prefs.deleteAfterRead) private var deleteAfterRead = false
    @EnvironmentObject private var downloads: DownloadManager

    var body: some View {
        Form {
            Section {
                Toggle("Scarica solo con Wi-Fi", isOn: $wifiOnly)
                    .onChange(of: wifiOnly) { _, _ in downloads.startIfNeeded() }
            }
            Section {
                Toggle("Scarica nuovi capitoli", isOn: $autoNew)
            } header: {
                Text("Download automatico")
            } footer: {
                Text("I capitoli trovati durante l'aggiornamento della libreria vengono scaricati automaticamente.")
            }
            Section("Elimina capitoli") {
                Toggle("Elimina dopo la lettura", isOn: $deleteAfterRead)
            }
        }
        .navigationTitle("Download")
    }
}

struct TrackingSettings: View {
    var body: some View {
        Form {
            Section {
                ForEach(["AniList", "MyAnimeList", "Kitsu", "MangaUpdates"], id: \.self) { name in
                    HStack {
                        Text(name)
                        Spacer()
                        Text("Prossimamente").foregroundStyle(.secondary)
                    }
                }
            } footer: {
                Text("Il tracciamento richiede la registrazione dell'app presso ciascun servizio (client ID OAuth). Verrà aggiunto in una versione futura.")
            }
        }
        .navigationTitle("Tracciamento")
    }
}

struct BrowseSettings: View {
    @AppStorage(Prefs.showNSFW) private var showNSFW = false
    @AppStorage(Prefs.showUnsupported) private var showUnsupported = true
    @EnvironmentObject private var extensions: ExtensionStore

    var body: some View {
        Form {
            Section("Estensioni") {
                NavigationLink { RepositoriesView() } label: {
                    HStack {
                        Text("Repository estensioni")
                        Spacer()
                        Text("\(extensions.repos.count)").foregroundStyle(.secondary)
                    }
                }
                Toggle("Mostra estensioni non supportate", isOn: $showUnsupported)
                Button("Controlla aggiornamenti") { Task { await extensions.refresh() } }
            }
            Section {
                Toggle("Mostra contenuti 18+", isOn: $showNSFW)
            } header: {
                Text("Contenuti per adulti")
            }
            Section("Lingue") {
                NavigationLink("Lingue delle fonti") { LanguageFilterSheet() }
            }
            Section {
                LabeledContent("Estensioni nel repository", value: "\(extensions.available.count)")
                LabeledContent("Supportate da Yomu", value: "\(extensions.available.filter { extensions.isSupported($0) }.count)")
                LabeledContent("Installate", value: "\(extensions.installed.count)")
            } header: {
                Text("Compatibilità")
            } footer: {
                Text("Le estensioni Mihon sono APK Android e non girano su iOS. Yomu riproduce in Swift i motori più diffusi (Madara, MangaThemesia, MangaWorld, PizzaReader): le estensioni basate su questi motori funzionano usando l'indirizzo del sito preso dal repository.")
            }
        }
        .navigationTitle("Sfoglia")
    }
}

struct DataStorageSettings: View {
    @Environment(\.modelContext) private var context
    @EnvironmentObject private var extensions: ExtensionStore
    @EnvironmentObject private var downloads: DownloadManager
    @State private var backupURL: URL?
    @State private var showImporter = false
    @State private var message: String?
    @State private var downloadSize: Int64 = 0
    @State private var cacheSize = 0
    @State private var confirmDeleteDownloads = false

    var body: some View {
        Form {
            Section {
                Button("Crea backup") {
                    do { backupURL = try BackupService.create(context: context, extensions: extensions) }
                    catch { message = error.localizedDescription }
                }
                if let backupURL {
                    ShareLink(item: backupURL) { Label("Salva o condividi il backup", systemImage: "square.and.arrow.up") }
                }
                Button("Ripristina backup") { showImporter = true }
            } header: {
                Text("Backup e ripristino")
            } footer: {
                Text("Il backup contiene libreria, categorie, capitoli letti, repository ed estensioni installate.")
            }
            Section("Spazio di archiviazione") {
                LabeledContent("Capitoli scaricati", value: ByteCountFormatter.string(fromByteCount: downloadSize, countStyle: .file))
                LabeledContent("Cache immagini", value: ByteCountFormatter.string(fromByteCount: Int64(cacheSize), countStyle: .file))
                Button("Svuota cache immagini") {
                    ImageLoader.clearDiskCache()
                    ImageLoader.shared.clearMemory()
                    refreshSizes()
                }
                Button("Elimina tutti i download", role: .destructive) { confirmDeleteDownloads = true }
            }
            if let message {
                Text(message).font(.caption)
            }
        }
        .navigationTitle("Dati e archiviazione")
        .onAppear(perform: refreshSizes)
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.json]) { result in
            switch result {
            case .success(let url):
                Task {
                    do {
                        let count = try await BackupService.restore(from: url, context: context, extensions: extensions)
                        message = "Ripristinati \(count) manga"
                    } catch {
                        message = "Errore: \(error.localizedDescription)"
                    }
                }
            case .failure(let error):
                message = error.localizedDescription
            }
        }
        .confirmationDialog("Eliminare tutti i capitoli scaricati?", isPresented: $confirmDeleteDownloads, titleVisibility: .visible) {
            Button("Elimina", role: .destructive) {
                downloads.deleteAll()
                refreshSizes()
            }
        }
    }

    private func refreshSizes() {
        downloadSize = DownloadManager.totalSize
        cacheSize = ImageLoader.diskCacheSize
    }
}

struct SecuritySettings: View {
    @AppStorage(Prefs.appLock) private var appLock = false
    @AppStorage(Prefs.secureScreen) private var secureScreen = false
    @AppStorage(Prefs.incognito) private var incognito = false

    var body: some View {
        Form {
            Section {
                Toggle("Blocco app (Face ID / codice)", isOn: $appLock)
                Toggle("Schermo protetto", isOn: $secureScreen)
            } footer: {
                Text("Lo schermo protetto nasconde il contenuto nel selettore delle app.")
            }
            Section {
                Toggle("Modalità incognito", isOn: $incognito)
            } footer: {
                Text("In modalità incognito la cronologia di lettura non viene salvata.")
            }
        }
        .navigationTitle("Sicurezza e privacy")
    }
}

struct AdvancedSettings: View {
    @AppStorage(Prefs.userAgent) private var userAgent = ""
    @State private var message: String?

    var body: some View {
        Form {
            Section {
                TextField(HTTPClient.defaultUserAgent, text: $userAgent, axis: .vertical)
                    .font(.caption)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Ripristina predefinito") { userAgent = "" }
            } header: {
                Text("User-Agent")
            }
            Section("Rete") {
                Button("Elimina i cookie") {
                    HTTPClient.clearCookies()
                    message = "Cookie eliminati"
                }
                Button("Svuota cache immagini") {
                    ImageLoader.clearDiskCache()
                    ImageLoader.shared.clearMemory()
                    message = "Cache svuotata"
                }
            }
            if let message { Text(message).font(.caption) }
        }
        .navigationTitle("Avanzate")
    }
}
