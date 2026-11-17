import Foundation
import SwiftData
import Network
import CryptoKit

/// Coda dei download: salva le pagine dei capitoli in Documents/Downloads.
@MainActor
final class DownloadManager: ObservableObject {
    struct Item: Identifiable, Hashable {
        let id: String
        let mangaKey: String
        let mangaTitle: String
        let chapterName: String
        let chapterURL: String
        let sourceId: String
        var progress: Double = 0
        var error: String?
    }

    @Published private(set) var queue: [Item] = []
    @Published var paused = false
    @Published private(set) var onWiFi = true

    private let container: ModelContainer
    private let extensions: ExtensionStore
    private var worker: Task<Void, Never>?
    private let monitor = NWPathMonitor()

    init(container: ModelContainer, extensions: ExtensionStore) {
        self.container = container
        self.extensions = extensions
        monitor.pathUpdateHandler = { [weak self] path in
            let wifi = path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet)
            Task { @MainActor in
                self?.onWiFi = wifi
                self?.startIfNeeded()
            }
        }
        monitor.start(queue: DispatchQueue(label: "yomu.network"))
    }

    // MARK: Percorsi

    static var root: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Downloads")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func hash(_ s: String) -> String {
        SHA256.hash(data: Data(s.utf8)).prefix(10).map { String(format: "%02x", $0) }.joined()
    }

    static func directory(mangaKey: String, chapterKey: String) -> URL {
        root.appendingPathComponent(hash(mangaKey)).appendingPathComponent(hash(chapterKey))
    }

    static func localPages(for chapter: ChapterEntity) -> [PageItem]? {
        guard let mangaKey = chapter.manga?.key else { return nil }
        let dir = directory(mangaKey: mangaKey, chapterKey: chapter.key)
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil),
              !files.isEmpty else { return nil }
        return files
            .filter { !$0.lastPathComponent.hasPrefix(".") }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
            .enumerated()
            .map { PageItem(index: $0.offset, imageURL: $0.element.absoluteString, referer: nil) }
    }

    static var totalSize: Int64 {
        guard let enumerator = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        var total: Int64 = 0
        for case let url as URL in enumerator {
            total += Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        return total
    }

    // MARK: Coda

    func enqueue(_ chapters: [ChapterEntity], manga: MangaEntity) {
        let ordered = chapters.sorted { $0.sourceOrder > $1.sourceOrder }
        for c in ordered where !c.isDownloaded && !queue.contains(where: { $0.id == c.key }) {
            queue.append(Item(
                id: c.key, mangaKey: manga.key, mangaTitle: manga.title, chapterName: c.name,
                chapterURL: c.url, sourceId: manga.sourceId
            ))
        }
        startIfNeeded()
    }

    func isQueued(_ chapter: ChapterEntity) -> Item? { queue.first { $0.id == chapter.key } }

    func cancel(_ item: Item) {
        queue.removeAll { $0.id == item.id }
        try? FileManager.default.removeItem(at: Self.directory(mangaKey: item.mangaKey, chapterKey: item.id).appendingPathExtension("tmp"))
    }

    func clearQueue() {
        for item in queue { cancel(item) }
        worker?.cancel()
        worker = nil
    }

    func retryFailed() {
        for i in queue.indices { queue[i].error = nil }
        startIfNeeded()
    }

    func delete(_ chapters: [ChapterEntity]) {
        for c in chapters {
            guard let mangaKey = c.manga?.key else { continue }
            try? FileManager.default.removeItem(at: Self.directory(mangaKey: mangaKey, chapterKey: c.key))
            c.isDownloaded = false
        }
        try? container.mainContext.save()
    }

    func deleteAll() {
        clearQueue()
        try? FileManager.default.removeItem(at: Self.root)
        let context = container.mainContext
        let downloaded = (try? context.fetch(FetchDescriptor<ChapterEntity>(predicate: #Predicate { $0.isDownloaded == true }))) ?? []
        for c in downloaded { c.isDownloaded = false }
        try? context.save()
    }

    var blockedByNetwork: Bool {
        UserDefaults.standard.bool(forKey: Prefs.downloadWiFiOnly) && !onWiFi
    }

    func startIfNeeded() {
        guard worker == nil, !paused, !blockedByNetwork, queue.contains(where: { $0.error == nil }) else { return }
        worker = Task { [weak self] in
            await self?.run()
            self?.worker = nil
        }
    }

    private func run() async {
        while !Task.isCancelled, !paused, !blockedByNetwork,
              let item = queue.first(where: { $0.error == nil }) {
            do {
                try await download(item)
                queue.removeAll { $0.id == item.id }
                markDownloaded(item)
            } catch {
                if let i = queue.firstIndex(where: { $0.id == item.id }) {
                    queue[i].error = error.localizedDescription
                }
            }
        }
    }

    private func download(_ item: Item) async throws {
        guard let source = extensions.source(id: item.sourceId) else { throw SourceError.sourceUnavailable }
        let chapter = SChapter(url: item.chapterURL, name: item.chapterName)
        let pages = try await source.pages(chapter)
        guard !pages.isEmpty else { throw SourceError.parse("nessuna pagina") }
        let final = Self.directory(mangaKey: item.mangaKey, chapterKey: item.id)
        let temp = final.appendingPathExtension("tmp")
        let fm = FileManager.default
        try? fm.removeItem(at: temp)
        try fm.createDirectory(at: temp, withIntermediateDirectories: true)
        for page in pages {
            try Task.checkCancellation()
            guard queue.contains(where: { $0.id == item.id }) else { throw CancellationError() }
            var data: Data?
            for attempt in 0..<3 where data == nil {
                do {
                    data = try await HTTPClient.shared.request(page.imageURL, headers: source.imageHeaders(for: page)).0
                } catch {
                    if attempt == 2 { throw error }
                    try await Task.sleep(nanoseconds: 800_000_000)
                }
            }
            let name = String(format: "%04d.img", page.index + 1)
            try data?.write(to: temp.appendingPathComponent(name))
            if let i = queue.firstIndex(where: { $0.id == item.id }) {
                queue[i].progress = Double(page.index + 1) / Double(pages.count)
            }
        }
        try? fm.removeItem(at: final)
        try fm.createDirectory(at: final.deletingLastPathComponent(), withIntermediateDirectories: true)
        try fm.moveItem(at: temp, to: final)
    }

    private func markDownloaded(_ item: Item) {
        let context = container.mainContext
        let key = item.id
        var d = FetchDescriptor<ChapterEntity>(predicate: #Predicate { $0.key == key })
        d.fetchLimit = 1
        if let c = try? context.fetch(d).first {
            c.isDownloaded = true
            try? context.save()
        }
    }
}
