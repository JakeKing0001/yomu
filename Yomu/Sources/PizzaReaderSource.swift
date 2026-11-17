import Foundation

/// Porting in Swift di PizzaReader: un'API JSON usata da molti team di scan italiani.
final class PizzaReaderSource: MangaSource {
    let info: SourceInfo
    private let apiPath: String

    init(info: SourceInfo, theme: ThemeInfo) {
        self.info = info
        self.apiPath = theme.api ?? "/api"
    }

    private var apiURL: String { baseURL + apiPath }

    private struct ResultsDTO: Decodable { let comics: [ComicDTO]? }
    private struct ResultDTO: Decodable { let comic: ComicDTO? }
    private struct ReaderDTO: Decodable { let chapter: ChapterDTO? }
    private struct NamedDTO: Decodable { let name: String? }

    private struct ComicDTO: Decodable {
        let title: String?
        let thumbnail: String?
        let url: String?
        let author: String?
        let artist: String?
        let description: String?
        let status: String?
        let genres: [NamedDTO]?
        let chapters: [ChapterDTO]?
        let last_chapter: ChapterDTO?
    }

    private struct ChapterDTO: Decodable {
        let chapter: Int?
        let subchapter: Int?
        let full_title: String?
        let url: String?
        let published_on: String?
        let pages: [String]?
        let teams: [NamedDTO?]?
    }

    private func manga(_ comic: ComicDTO) -> SManga {
        SManga(url: comic.url ?? "", title: comic.title ?? "", thumbnailURL: comic.thumbnail)
    }

    func popular(page: Int) async throws -> MangasPage {
        let result = try await HTTPClient.shared.json(ResultsDTO.self, "\(apiURL)/comics")
        return MangasPage(mangas: (result.comics ?? []).map(manga), hasNextPage: false)
    }

    func latest(page: Int) async throws -> MangasPage {
        let result = try await HTTPClient.shared.json(ResultsDTO.self, "\(apiURL)/comics")
        let sorted = (result.comics ?? [])
            .filter { $0.last_chapter != nil }
            .sorted { ($0.last_chapter?.published_on ?? "") > ($1.last_chapter?.published_on ?? "") }
            .prefix(30)
        return MangasPage(mangas: sorted.map(manga), hasNextPage: false)
    }

    func search(query: String, page: Int) async throws -> MangasPage {
        let result = try await HTTPClient.shared.json(ResultsDTO.self, "\(apiURL)/search/\(HTTPClient.encodePath(query))")
        return MangasPage(mangas: (result.comics ?? []).map(manga), hasNextPage: false)
    }

    func details(_ input: SManga) async throws -> (SManga, [SChapter]) {
        let result = try await HTTPClient.shared.json(ResultDTO.self, apiURL + input.url)
        guard let comic = result.comic else { throw SourceError.parse("fumetto") }
        var m = input
        m.title = comic.title ?? input.title
        m.author = comic.author?.nilIfEmpty
        m.artist = comic.artist?.nilIfEmpty
        m.description = comic.description
        m.genres = (comic.genres ?? []).compactMap(\.name)
        m.thumbnailURL = comic.thumbnail ?? input.thumbnailURL
        m.status = Self.status(comic.status)
        let chapters = (comic.chapters ?? []).map { c -> SChapter in
            let number = Double(c.chapter ?? -1) + (Double("0.\(c.subchapter ?? 0)") ?? 0)
            let teams = (c.teams ?? []).compactMap { $0?.name }.joined(separator: " & ")
            return SChapter(
                url: c.url ?? "",
                name: c.full_title ?? "Capitolo \(c.chapter ?? 0)",
                number: number,
                dateUpload: Self.date(c.published_on),
                scanlator: teams.nilIfEmpty
            )
        }
        return (m, chapters)
    }

    func pages(_ chapter: SChapter) async throws -> [PageItem] {
        let result = try await HTTPClient.shared.json(ReaderDTO.self, apiURL + chapter.url)
        return (result.chapter?.pages ?? []).enumerated().map {
            PageItem(index: $0.offset, imageURL: $0.element.absolute(base: baseURL), referer: baseURL + "/")
        }
    }

    func mangaWebURL(_ manga: SManga) -> URL? { URL(string: baseURL + manga.url) }

    private static func status(_ s: String?) -> MangaStatus {
        switch String((s ?? "").prefix(7)) {
        case "In cors", "On goin": return .ongoing
        case "Complet", "Conclus", "Conclud": return .completed
        case "Licenzi", "License": return .licensed
        default: return .unknown
        }
    }

    private static func date(_ s: String?) -> Date? {
        guard let s else { return nil }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s) ?? DateParser.parse(s, format: "yyyy-MM-dd HH:mm:ss")
    }
}
