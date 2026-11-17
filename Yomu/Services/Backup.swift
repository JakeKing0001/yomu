import Foundation
import SwiftData

/// Backup manuale in JSON (libreria, capitoli letti, categorie, repository ed estensioni).
struct BackupFile: Codable {
    struct Category: Codable { let id: String; let name: String; let order: Int }
    struct Chapter: Codable {
        let url: String
        let name: String
        let number: Double
        let read: Bool
        let bookmarked: Bool
        let lastPageRead: Int
        let lastReadAt: Date?
        let dateUpload: Date?
        let sourceOrder: Int
    }
    struct Manga: Codable {
        let sourceId: String
        let sourceName: String
        let url: String
        let title: String
        let thumbnailURL: String?
        let author: String?
        let artist: String?
        let summary: String?
        let genres: [String]
        let status: Int
        let dateAdded: Date
        let categoryIDs: [String]
        let readerMode: Int
        let chapters: [Chapter]
    }

    var version = 1
    var created = Date()
    var categories: [Category]
    var mangas: [Manga]
    var repos: [String]
    var installedPackages: [String]
}

@MainActor
enum BackupService {
    static func create(context: ModelContext, extensions: ExtensionStore) throws -> URL {
        let categories = (try? context.fetch(FetchDescriptor<CategoryEntity>())) ?? []
        let mangas = (try? context.fetch(FetchDescriptor<MangaEntity>(predicate: #Predicate { $0.inLibrary == true }))) ?? []
        let file = BackupFile(
            categories: categories.map { .init(id: $0.id, name: $0.name, order: $0.order) },
            mangas: mangas.map { m in
                BackupFile.Manga(
                    sourceId: m.sourceId, sourceName: m.sourceName, url: m.url, title: m.title,
                    thumbnailURL: m.thumbnailURL, author: m.author, artist: m.artist, summary: m.summary,
                    genres: m.genres, status: m.status, dateAdded: m.dateAdded, categoryIDs: m.categoryIDs,
                    readerMode: m.readerMode,
                    chapters: m.chapters.map { c in
                        BackupFile.Chapter(
                            url: c.url, name: c.name, number: c.number, read: c.read, bookmarked: c.bookmarked,
                            lastPageRead: c.lastPageRead, lastReadAt: c.lastReadAt, dateUpload: c.dateUpload,
                            sourceOrder: c.sourceOrder
                        )
                    }
                )
            },
            repos: extensions.repos.map(\.url),
            installedPackages: Array(extensions.installed.keys)
        )
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(file)
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd_HH-mm"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("yomu_\(formatter.string(from: Date())).json")
        try data.write(to: url, options: .atomic)
        return url
    }

    static func restore(from url: URL, context: ModelContext, extensions: ExtensionStore) async throws -> Int {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: url)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let file = try decoder.decode(BackupFile.self, from: data)

        let existingCategories = (try? context.fetch(FetchDescriptor<CategoryEntity>())) ?? []
        for c in file.categories where !existingCategories.contains(where: { $0.id == c.id }) {
            let entity = CategoryEntity(name: c.name, order: c.order)
            entity.id = c.id
            context.insert(entity)
        }
        for m in file.mangas {
            let info = SourceInfo(id: m.sourceId, name: m.sourceName, lang: "", baseURL: "")
            let s = SManga(url: m.url, title: m.title, thumbnailURL: m.thumbnailURL, author: m.author, artist: m.artist,
                           description: m.summary, genres: m.genres, status: MangaStatus(rawValue: m.status) ?? .unknown)
            let entity = MangaRepository.upsert(s, source: info, in: context)
            entity.inLibrary = true
            entity.dateAdded = m.dateAdded
            entity.categoryIDs = m.categoryIDs
            entity.readerMode = m.readerMode
            let chapters = m.chapters.sorted { $0.sourceOrder < $1.sourceOrder }.map {
                SChapter(url: $0.url, name: $0.name, number: $0.number, dateUpload: $0.dateUpload)
            }
            MangaRepository.sync(chapters, to: entity, in: context, markNew: false)
            let byURL = Dictionary(m.chapters.map { ($0.url, $0) }, uniquingKeysWith: { a, _ in a })
            for c in entity.chapters {
                guard let b = byURL[c.url] else { continue }
                c.read = c.read || b.read
                c.bookmarked = c.bookmarked || b.bookmarked
                c.lastPageRead = max(c.lastPageRead, b.lastPageRead)
                if let d = b.lastReadAt { c.lastReadAt = max(c.lastReadAt ?? d, d) }
            }
        }
        try context.save()
        for repo in file.repos where !extensions.repos.contains(where: { $0.url == repo }) {
            try? await extensions.addRepo(repo)
        }
        for pkg in file.installedPackages {
            if let ext = extensions.available.first(where: { $0.pkg == pkg }) { extensions.install(ext) }
        }
        return file.mangas.count
    }
}
