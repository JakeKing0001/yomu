import Foundation
import SwiftUI

/// Gestisce repository, estensioni installate e creazione delle fonti.
@MainActor
final class ExtensionStore: ObservableObject {
    struct Repo: Codable, Hashable, Identifiable {
        var url: String
        var name: String
        var id: String { url }
    }

    @Published private(set) var repos: [Repo] = []
    @Published private(set) var available: [RepoExtension] = []
    @Published private(set) var installed: [String: RepoExtension] = [:]
    @Published private(set) var isRefreshing = false
    @Published var lastError: String?
    @Published var pinnedSources: Set<String> = []
    @Published var lastUsedSource: String?

    let themes: [String: ThemeInfo]
    private var sourceCache: [String: MangaSource] = [:]

    private let defaults = UserDefaults.standard
    private let reposKey = "repos.v1"
    private let pinnedKey = "sources.pinned"
    private let lastUsedKey = "sources.lastUsed"

    init() {
        if let url = Bundle.main.url(forResource: "themes", withExtension: "json"),
           let data = try? Data(contentsOf: url),
           let map = try? JSONDecoder().decode([String: ThemeInfo].self, from: data) {
            themes = map
        } else {
            themes = [:]
        }
        if let data = defaults.data(forKey: reposKey), let list = try? JSONDecoder().decode([Repo].self, from: data) {
            repos = list
        }
        if let data = try? Data(contentsOf: Self.installedFile),
           let map = try? JSONDecoder().decode([String: RepoExtension].self, from: data) {
            installed = map
        }
        pinnedSources = Set(defaults.stringArray(forKey: pinnedKey) ?? [])
        lastUsedSource = defaults.string(forKey: lastUsedKey)
        loadCachedIndexes()
    }

    // MARK: File

    private static var supportDir: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private static var installedFile: URL { supportDir.appendingPathComponent("installed-extensions.json") }

    private static func cacheFile(for repo: String) -> URL {
        let safe = repo.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) ? String($0) : "_" }.joined()
        return supportDir.appendingPathComponent("repo-\(safe.suffix(80)).bin")
    }

    private func saveRepos() {
        if let data = try? JSONEncoder().encode(repos) { defaults.set(data, forKey: reposKey) }
    }

    private func saveInstalled() {
        if let data = try? JSONEncoder().encode(installed) { try? data.write(to: Self.installedFile, options: .atomic) }
    }

    private func loadCachedIndexes() {
        var all: [RepoExtension] = []
        for repo in repos {
            if let data = try? Data(contentsOf: Self.cacheFile(for: repo.url)),
               let index = try? RepoParser.parse(data, repoURL: repo.url) {
                all += index.extensions
            }
        }
        available = all
    }

    // MARK: Repository

    func addRepo(_ input: String) async throws {
        let candidates = RepoParser.candidateURLs(for: input)
        var lastError: Error = RepoParserError.invalid("URL")
        for url in candidates {
            do {
                let (data, _) = try await HTTPClient.shared.request(url)
                let index = try RepoParser.parse(data, repoURL: url)
                if index.extensions.count <= 2, index.extensions.contains(where: { $0.name.contains("Outdated") || $0.name.contains("Update to") }) {
                    // Indice "segnaposto" per app vecchie: si prova il formato successivo.
                    lastError = RepoParserError.invalid("indice obsoleto")
                    continue
                }
                try? data.write(to: Self.cacheFile(for: url), options: .atomic)
                if !repos.contains(where: { $0.url == url }) {
                    repos.append(Repo(url: url, name: index.name))
                    saveRepos()
                }
                loadCachedIndexes()
                return
            } catch {
                lastError = error
            }
        }
        throw lastError
    }

    func removeRepo(_ repo: Repo) {
        repos.removeAll { $0.url == repo.url }
        try? FileManager.default.removeItem(at: Self.cacheFile(for: repo.url))
        saveRepos()
        loadCachedIndexes()
    }

    func refresh() async {
        guard !isRefreshing else { return }
        isRefreshing = true
        defer { isRefreshing = false }
        lastError = nil
        for repo in repos {
            do {
                let (data, _) = try await HTTPClient.shared.request(repo.url)
                let index = try RepoParser.parse(data, repoURL: repo.url)
                try? data.write(to: Self.cacheFile(for: repo.url), options: .atomic)
                if let i = repos.firstIndex(of: repo) { repos[i].name = index.name }
            } catch {
                lastError = "\(repo.name): \(error.localizedDescription)"
            }
        }
        saveRepos()
        loadCachedIndexes()
        // Aggiorna i dati (es. baseUrl) delle estensioni installate.
        for (pkg, _) in installed {
            if let fresh = available.first(where: { $0.pkg == pkg }) { installed[pkg] = fresh }
        }
        sourceCache.removeAll()
        saveInstalled()
    }

    // MARK: Estensioni

    func theme(for ext: RepoExtension) -> ThemeInfo? {
        guard let t = themes[ext.themeKey], SourceFactory.supportedThemes.contains(t.t) else { return nil }
        return t
    }

    func isSupported(_ ext: RepoExtension) -> Bool { theme(for: ext) != nil }

    func isInstalled(_ ext: RepoExtension) -> Bool { installed[ext.pkg] != nil }

    func hasUpdate(_ ext: RepoExtension) -> Bool {
        guard let current = installed[ext.pkg] else { return false }
        return current.version != ext.version
    }

    func install(_ ext: RepoExtension) {
        guard isSupported(ext) else { return }
        installed[ext.pkg] = ext
        saveInstalled()
    }

    func uninstall(_ ext: RepoExtension) {
        installed[ext.pkg] = nil
        for s in ext.sources { sourceCache[s.id] = nil }
        saveInstalled()
    }

    var updatableCount: Int {
        available.filter { hasUpdate($0) }.count
    }

    // MARK: Fonti

    /// Fonti delle estensioni installate, filtrate per le lingue attive.
    var installedSources: [SourceInfo] {
        let langs = enabledLanguages
        return installed.values
            .flatMap { $0.sources }
            .filter { langs.isEmpty || langs.contains($0.lang) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    func source(id: String) -> MangaSource? {
        if let cached = sourceCache[id] { return cached }
        let pool = Array(installed.values) + available
        for ext in pool {
            guard let info = ext.sources.first(where: { $0.id == id }), let theme = theme(for: ext) else { continue }
            if let source = SourceFactory.make(info: info, theme: theme) {
                sourceCache[id] = source
                return source
            }
        }
        return nil
    }

    func sourceInfo(id: String) -> SourceInfo? {
        for ext in Array(installed.values) + available {
            if let s = ext.sources.first(where: { $0.id == id }) { return s }
        }
        return nil
    }

    func extensionFor(sourceId: String) -> RepoExtension? {
        (Array(installed.values) + available).first { $0.sources.contains { $0.id == sourceId } }
    }

    func togglePin(_ id: String) {
        if pinnedSources.contains(id) { pinnedSources.remove(id) } else { pinnedSources.insert(id) }
        defaults.set(Array(pinnedSources), forKey: pinnedKey)
    }

    func markUsed(_ id: String) {
        lastUsedSource = id
        defaults.set(id, forKey: lastUsedKey)
    }

    // MARK: Lingue

    var enabledLanguages: Set<String> {
        get {
            if let list = defaults.stringArray(forKey: Prefs.enabledLanguages) { return Set(list) }
            var base: Set<String> = ["all", "it", "en"]
            if let device = Locale.current.language.languageCode?.identifier { base.insert(device) }
            return base
        }
        set {
            objectWillChange.send()
            defaults.set(Array(newValue), forKey: Prefs.enabledLanguages)
        }
    }

    var availableLanguages: [String] {
        Array(Set(available.map(\.lang) + installed.values.map(\.lang))).sorted { a, b in
            if a == "all" { return true }
            if b == "all" { return false }
            return Self.languageName(a) < Self.languageName(b)
        }
    }

    static func languageName(_ code: String) -> String {
        switch code {
        case "all": return "Multilingua"
        case "other": return "Altro"
        default:
            let name = Locale(identifier: "it_IT").localizedString(forIdentifier: code) ?? code
            return name.prefix(1).uppercased() + name.dropFirst()
        }
    }
}
