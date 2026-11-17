import Foundation
import SwiftData

/// Operazioni comuni sul database: salvataggio manga, sincronizzazione capitoli, migrazione.
@MainActor
enum MangaRepository {
    static func manga(key: String, in context: ModelContext) -> MangaEntity? {
        var descriptor = FetchDescriptor<MangaEntity>(predicate: #Predicate { $0.key == key })
        descriptor.fetchLimit = 1
        return try? context.fetch(descriptor).first
    }

    @discardableResult
    static func upsert(_ s: SManga, source: SourceInfo, in context: ModelContext) -> MangaEntity {
        let key = MangaEntity.makeKey(sourceId: source.id, url: s.url)
        let entity: MangaEntity
        if let existing = manga(key: key, in: context) {
            entity = existing
        } else {
            entity = MangaEntity(sourceId: source.id, sourceName: source.name, url: s.url, title: s.title)
            context.insert(entity)
        }
        apply(s, to: entity)
        return entity
    }

    static func apply(_ s: SManga, to entity: MangaEntity) {
        if !s.title.isEmpty { entity.title = s.title }
        if let t = s.thumbnailURL, !t.isEmpty { entity.thumbnailURL = t }
        if let v = s.author { entity.author = v }
        if let v = s.artist { entity.artist = v }
        if let v = s.description { entity.summary = v }
        if !s.genres.isEmpty { entity.genres = s.genres }
        if s.status != .unknown || entity.status == 0 { entity.status = s.status.rawValue }
    }

    /// Allinea i capitoli salvati a quelli della fonte. Restituisce i capitoli nuovi.
    @discardableResult
    static func sync(_ chapters: [SChapter], to manga: MangaEntity, in context: ModelContext, markNew: Bool) -> [ChapterEntity] {
        var existing: [String: ChapterEntity] = [:]
        for c in manga.chapters { existing[c.url] = c }
        let firstFetch = manga.chapters.isEmpty
        var added: [ChapterEntity] = []
        var seen = Set<String>()
        let now = Date()
        for (index, s) in chapters.enumerated() {
            guard seen.insert(s.url).inserted else { continue }
            if let c = existing[s.url] {
                c.name = s.name
                c.number = s.number
                c.scanlator = s.scanlator
                if let d = s.dateUpload { c.dateUpload = d }
                c.sourceOrder = index
            } else {
                let c = ChapterEntity(mangaKey: manga.key, url: s.url, name: s.name)
                c.number = s.number
                c.scanlator = s.scanlator
                c.dateUpload = s.dateUpload
                c.sourceOrder = index
                c.dateFetched = now
                c.isNew = markNew && !firstFetch
                context.insert(c)
                c.manga = manga
                added.append(c)
            }
        }
        // I capitoli spariti dalla fonte vengono rimossi, tranne quelli scaricati.
        for c in Array(manga.chapters) where !seen.contains(c.url) && !c.isDownloaded && !added.contains(where: { $0 === c }) {
            context.delete(c)
        }
        if !added.isEmpty { manga.lastUpdate = now }
        return added
    }

    static func refresh(_ manga: MangaEntity, source: MangaSource, in context: ModelContext, markNew: Bool = false) async throws -> [ChapterEntity] {
        let input = SManga(url: manga.url, title: manga.title, thumbnailURL: manga.thumbnailURL)
        let (details, chapters) = try await source.details(input)
        apply(details, to: manga)
        let added = sync(chapters, to: manga, in: context, markNew: markNew)
        try? context.save()
        return added
    }

    static func setRead(_ chapters: [ChapterEntity], read: Bool) {
        for c in chapters {
            c.read = read
            if !read { c.lastPageRead = 0 }
            c.isNew = false
        }
    }

    /// Sposta un manga su un'altra fonte mantenendo progressi, categorie e libreria.
    static func migrate(_ old: MangaEntity, to target: SManga, source: MangaSource, in context: ModelContext, removeOld: Bool) async throws -> MangaEntity {
        let (details, chapters) = try await source.details(target)
        let new = upsert(details, source: source.info, in: context)
        sync(chapters, to: new, in: context, markNew: false)
        let readNumbers = old.chapters.filter(\.read).map(\.number)
        let maxRead = readNumbers.max() ?? -1
        for c in new.chapters where c.number >= 0 && c.number <= maxRead {
            c.read = true
        }
        let bookmarked = Set(old.chapters.filter(\.bookmarked).map(\.number))
        for c in new.chapters where bookmarked.contains(c.number) { c.bookmarked = true }
        new.inLibrary = true
        new.categoryIDs = old.categoryIDs
        new.dateAdded = old.dateAdded
        new.readerMode = old.readerMode
        if removeOld {
            if old.key != new.key { context.delete(old) }
        } else {
            old.inLibrary = false
        }
        try? context.save()
        return new
    }
}
