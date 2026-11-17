import Foundation
import SwiftData

/// Aggiornamento della libreria: scarica i nuovi capitoli di tutti i manga salvati.
@MainActor
final class LibraryUpdater: ObservableObject {
    @Published private(set) var isUpdating = false
    @Published private(set) var progress: (done: Int, total: Int) = (0, 0)
    @Published private(set) var currentTitle: String?
    @Published var errors: [String] = []
    @Published var lastResult: String?

    private let container: ModelContainer
    private let extensions: ExtensionStore
    weak var downloads: DownloadManager?

    init(container: ModelContainer, extensions: ExtensionStore) {
        self.container = container
        self.extensions = extensions
    }

    var lastUpdate: Date? {
        let t = UserDefaults.standard.double(forKey: Prefs.lastLibraryUpdate)
        return t > 0 ? Date(timeIntervalSince1970: t) : nil
    }

    func update(categoryID: String? = nil) async {
        guard !isUpdating else { return }
        isUpdating = true
        errors = []
        defer {
            isUpdating = false
            currentTitle = nil
        }
        let context = container.mainContext
        let all = (try? context.fetch(FetchDescriptor<MangaEntity>(predicate: #Predicate { $0.inLibrary == true }))) ?? []
        let onlyOngoing = UserDefaults.standard.bool(forKey: Prefs.updateOnlyOngoing)
        let skipCompletedRead = UserDefaults.standard.bool(forKey: Prefs.updateSkipRead)
        let targets = all.filter { manga in
            if let categoryID {
                if categoryID == LibraryView.defaultCategoryID { if !manga.categoryIDs.isEmpty { return false } }
                else if !manga.categoryIDs.contains(categoryID) { return false }
            }
            if onlyOngoing && manga.statusValue == .completed { return false }
            if skipCompletedRead && manga.unreadCount > 0 { return false }
            return true
        }
        progress = (0, targets.count)
        var newCount = 0
        let autoDownload = UserDefaults.standard.bool(forKey: Prefs.autoDownloadNew)
        for manga in targets {
            currentTitle = manga.title
            guard let source = extensions.source(id: manga.sourceId) else {
                errors.append("\(manga.title): fonte \(manga.sourceName) non installata")
                progress.done += 1
                continue
            }
            do {
                let added = try await MangaRepository.refresh(manga, source: source, in: context, markNew: true)
                newCount += added.count
                if autoDownload, !added.isEmpty { downloads?.enqueue(added, manga: manga) }
            } catch {
                errors.append("\(manga.title): \(error.localizedDescription)")
            }
            progress.done += 1
        }
        try? context.save()
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: Prefs.lastLibraryUpdate)
        lastResult = newCount == 0 ? "Nessun nuovo capitolo" : "\(newCount) nuovi capitoli"
        objectWillChange.send()
    }
}
