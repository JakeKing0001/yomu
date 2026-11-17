import Foundation

struct SManga: Hashable, Identifiable {
    var url: String
    var title: String
    var thumbnailURL: String?
    var author: String?
    var artist: String?
    var description: String?
    var genres: [String] = []
    var status: MangaStatus = .unknown

    var id: String { url }
}

struct SChapter: Hashable {
    var url: String
    var name: String
    var number: Double = -1
    var dateUpload: Date?
    var scanlator: String?
}

struct PageItem: Hashable, Identifiable {
    var index: Int
    var imageURL: String
    var referer: String?

    var id: Int { index }
}

struct MangasPage {
    var mangas: [SManga]
    var hasNextPage: Bool
}

/// Informazioni di una fonte così come appaiono nell'indice del repository.
struct SourceInfo: Codable, Hashable, Identifiable {
    let id: String
    let name: String
    let lang: String
    let baseURL: String
}

protocol MangaSource: AnyObject {
    var info: SourceInfo { get }
    var supportsLatest: Bool { get }

    func popular(page: Int) async throws -> MangasPage
    func latest(page: Int) async throws -> MangasPage
    func search(query: String, page: Int) async throws -> MangasPage
    /// Dettagli e lista capitoli (dal più recente al più vecchio).
    func details(_ manga: SManga) async throws -> (SManga, [SChapter])
    func pages(_ chapter: SChapter) async throws -> [PageItem]
    func imageHeaders(for page: PageItem) -> [String: String]
    func mangaWebURL(_ manga: SManga) -> URL?
    func chapterWebURL(_ chapter: SChapter) -> URL?
}

extension MangaSource {
    var id: String { info.id }
    var name: String { info.name }
    var lang: String { info.lang }
    var baseURL: String { info.baseURL }

    var supportsLatest: Bool { true }

    func imageHeaders(for page: PageItem) -> [String: String] {
        ["Referer": page.referer ?? (baseURL + "/")]
    }

    var coverHeaders: [String: String] { ["Referer": baseURL + "/"] }

    func mangaWebURL(_ manga: SManga) -> URL? { URL(string: manga.url.absolute(base: baseURL)) }

    func chapterWebURL(_ chapter: SChapter) -> URL? { URL(string: chapter.url.absolute(base: baseURL)) }

    func absolute(_ path: String) -> String { path.absolute(base: baseURL) }
}

/// Opzioni del tema lette da themes.json (generato da Tools/generate_themes.py).
struct ThemeInfo: Codable, Hashable {
    let t: String
    let cm: String?
    let ms: String?
    let na: Bool?
    let df: String?
    let dir: String?
    let api: String?

    var engineName: String {
        switch t {
        case "madara": return "Madara"
        case "mangathemesia": return "MangaThemesia"
        case "mangaworld": return "MangaWorld"
        case "pizzareader": return "PizzaReader"
        default: return t
        }
    }
}

enum SourceFactory {
    static func make(info: SourceInfo, theme: ThemeInfo) -> MangaSource? {
        switch theme.t {
        case "madara": return MadaraSource(info: info, theme: theme)
        case "mangathemesia": return MangaThemesiaSource(info: info, theme: theme)
        case "mangaworld": return MangaWorldSource(info: info)
        case "pizzareader": return PizzaReaderSource(info: info, theme: theme)
        default: return nil
        }
    }

    static let supportedThemes: Set<String> = ["madara", "mangathemesia", "mangaworld", "pizzareader"]
}
