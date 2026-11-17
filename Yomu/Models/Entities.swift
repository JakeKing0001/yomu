import Foundation
import SwiftData

@Model
final class MangaEntity {
    @Attribute(.unique) var key: String
    var sourceId: String
    var sourceName: String
    var url: String
    var title: String
    var author: String?
    var artist: String?
    var summary: String?
    var genres: [String] = []
    var status: Int = 0
    var thumbnailURL: String?
    var inLibrary: Bool = false
    var dateAdded: Date = Date.distantPast
    var lastUpdate: Date?
    var categoryIDs: [String] = []
    var readerMode: Int = -1
    var chapterSortAscending: Bool = false
    @Relationship(deleteRule: .cascade, inverse: \ChapterEntity.manga)
    var chapters: [ChapterEntity] = []

    init(sourceId: String, sourceName: String, url: String, title: String) {
        self.key = MangaEntity.makeKey(sourceId: sourceId, url: url)
        self.sourceId = sourceId
        self.sourceName = sourceName
        self.url = url
        self.title = title
    }

    static func makeKey(sourceId: String, url: String) -> String { "\(sourceId)|\(url)" }

    var sortedChapters: [ChapterEntity] {
        chapters.sorted { $0.sourceOrder < $1.sourceOrder }
    }

    var unreadCount: Int { chapters.reduce(0) { $0 + ($1.read ? 0 : 1) } }
    var downloadedCount: Int { chapters.reduce(0) { $0 + ($1.isDownloaded ? 1 : 0) } }
    var lastReadAt: Date? { chapters.compactMap(\.lastReadAt).max() }

    var statusValue: MangaStatus { MangaStatus(rawValue: status) ?? .unknown }

    /// Primo capitolo non letto in ordine di lettura (dal più vecchio).
    var nextChapterToRead: ChapterEntity? {
        let ordered = chapters.sorted { $0.sourceOrder > $1.sourceOrder }
        if let resume = ordered.first(where: { !$0.read && $0.lastPageRead > 0 }) { return resume }
        return ordered.first(where: { !$0.read })
    }
}

@Model
final class ChapterEntity {
    @Attribute(.unique) var key: String
    var url: String
    var name: String
    var number: Double = -1
    var scanlator: String?
    var dateUpload: Date?
    var dateFetched: Date = Date()
    var read: Bool = false
    var bookmarked: Bool = false
    var lastPageRead: Int = 0
    var lastReadAt: Date?
    var sourceOrder: Int = 0
    var isNew: Bool = false
    var isDownloaded: Bool = false
    var manga: MangaEntity?

    init(mangaKey: String, url: String, name: String) {
        self.key = "\(mangaKey)#\(url)"
        self.url = url
        self.name = name
    }
}

@Model
final class CategoryEntity {
    @Attribute(.unique) var id: String
    var name: String
    var order: Int

    init(name: String, order: Int) {
        self.id = UUID().uuidString
        self.name = name
        self.order = order
    }
}

enum MangaStatus: Int, CaseIterable {
    case unknown = 0, ongoing, completed, licensed, publishingFinished, cancelled, onHiatus

    var label: String {
        switch self {
        case .unknown: return "Sconosciuto"
        case .ongoing: return "In corso"
        case .completed: return "Completato"
        case .licensed: return "Licenziato"
        case .publishingFinished: return "Pubblicazione terminata"
        case .cancelled: return "Cancellato"
        case .onHiatus: return "In pausa"
        }
    }

    var icon: String {
        switch self {
        case .ongoing: return "clock"
        case .completed, .publishingFinished: return "checkmark.circle"
        case .cancelled: return "xmark.circle"
        case .onHiatus: return "pause.circle"
        case .licensed: return "c.circle"
        case .unknown: return "questionmark.circle"
        }
    }
}
