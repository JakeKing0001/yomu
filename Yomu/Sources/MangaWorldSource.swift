import Foundation
import SwiftSoup

/// Porting in Swift di MangaWorld (lib-multisrc/mangaworld), usato da Mangaworld e MangaworldAdult.
final class MangaWorldSource: MangaSource {
    let info: SourceInfo

    init(info: SourceInfo) {
        self.info = info
    }

    private static let imageUserAgent = "Mozilla/5.0 (Linux; U; Android 4.1.1; en-gb; Build/KLP) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Safari/534.30"

    /// Il sito a volte risponde con una pagina che imposta il cookie MWCookie via JavaScript:
    /// lo si estrae, lo si salva e si ripete la richiesta (come CookieRedirectInterceptor).
    private func document(_ url: String) async throws -> Document {
        var (html, finalURL) = try await HTTPClient.shared.string(url, headers: ["Referer": baseURL + "/"])
        if let cookieString = Self.challengeCookie(in: html), let host = finalURL.host {
            let pair = cookieString.substring(before: ";")
            let name = pair.substring(before: "=")
            let value = pair.substring(after: "=") ?? ""
            if let cookie = HTTPCookie(properties: [
                .name: name, .value: value, .domain: host, .path: "/",
                .expires: Date().addingTimeInterval(60 * 60 * 24 * 30),
            ]) {
                HTTPCookieStorage.shared.setCookie(cookie)
            }
            (html, finalURL) = try await HTTPClient.shared.string(
                url, headers: ["Referer": baseURL + "/", "Cookie": "\(name)=\(value)"]
            )
        }
        return try SwiftSoup.parse(html, finalURL.absoluteString)
    }

    private static func challengeCookie(in html: String) -> String? {
        guard let start = html.range(of: "document.cookie=\"MWCookie") else { return nil }
        let rest = html[html.index(start.lowerBound, offsetBy: "document.cookie=\"".count)...]
        guard let end = rest.firstIndex(of: "\"") else { return nil }
        return String(rest[..<end])
    }

    func popular(page: Int) async throws -> MangasPage {
        parseList(try await document("\(baseURL)/archive?sort=most_read&page=\(page)"))
    }

    func latest(page: Int) async throws -> MangasPage {
        parseList(try await document("\(baseURL)/?page=\(page)"))
    }

    func search(query: String, page: Int) async throws -> MangasPage {
        parseList(try await document("\(baseURL)/archive?keyword=\(HTTPClient.encodeQuery(query))&page=\(page)"))
    }

    private func parseList(_ doc: Document) -> MangasPage {
        let mangas: [SManga] = doc.all("div.comics-grid .entry").compactMap { element in
            guard let link = element.first("a") else { return nil }
            var href = link.absURL("href")
            if href.hasSuffix("/") { href.removeLast() }
            let title = link.attrValue("title").nilIfEmpty ?? element.first(".name a")?.textValue ?? ""
            guard !href.isEmpty, !title.isEmpty else { return nil }
            return SManga(url: href.pathAndQuery, title: title, thumbnailURL: element.first("a.thumb img")?.absURL("src"))
        }
        return MangasPage(mangas: mangas, hasNextPage: mangas.count == 16)
    }

    func details(_ manga: SManga) async throws -> (SManga, [SChapter]) {
        let doc = try await document(absolute(manga.url))
        guard let box = doc.first("div.comic-info") else { throw SourceError.parse("pagina non trovata") }
        var result = manga
        if let title = box.first("h1")?.textValue, !title.isEmpty { result.title = title }
        result.author = box.first("a[href*=/archive?author=]")?.textValue
        result.artist = box.first("a[href*=/archive?artist=]")?.textValue
        if let thumb = box.first(".thumb > img")?.absURL("src"), !thumb.isEmpty { result.thumbnailURL = thumb }
        var description = doc.all("div#noidungm").map(\.textValue).joined(separator: " ")
        if let other = doc.first("div.meta-data > div")?.textValue, other.contains("Titoli alternativi") {
            description += "\n\n" + other
        }
        result.description = description.nilIfEmpty
        result.genres = box.all("div.meta-data a.badge").map(\.textValue)
        switch box.first("a[href*=/archive?status=]")?.textValue.lowercased() {
        case "in corso": result.status = .ongoing
        case "finito": result.status = .completed
        case "in pausa": result.status = .onHiatus
        case "cancellato": result.status = .cancelled
        default: result.status = .unknown
        }

        let chapters: [SChapter] = doc.all(".chapters-wrapper .chapter").compactMap { element in
            guard let link = element.first("a.chap") else { return nil }
            let url = Self.fixChapterURL(link.absURL("href"))
            guard !url.isEmpty else { return nil }
            let name = element.first("span.d-inline-block")?.textValue ?? link.textValue
            return SChapter(
                url: url.pathAndQuery,
                name: name,
                number: Self.chapterNumber(name),
                dateUpload: DateParser.parse(element.all(".chap-date").last?.textValue, format: "d MMMM yyyy", lang: "it_IT")
            )
        }
        return (result, chapters)
    }

    private static func fixChapterURL(_ url: String) -> String {
        guard !url.isEmpty else { return "" }
        let params = url.substring(after: "?") ?? ""
        if params.contains("style=list") { return url }
        if params.contains("style=pages") { return url.replacingOccurrences(of: "style=pages", with: "style=list") }
        return url + (params.isEmpty ? "?style=list" : "&style=list")
    }

    private static func chapterNumber(_ name: String) -> Double {
        if let r = name.range(of: #"(?i)capitolo\s([0-9]+(?:\.[0-9]+)?)"#, options: .regularExpression) {
            let digits = name[r].components(separatedBy: CharacterSet(charactersIn: "0123456789.").inverted).joined()
            if let v = Double(digits) { return v }
        }
        return ChapterRecognition.number(from: name)
    }

    func pages(_ chapter: SChapter) async throws -> [PageItem] {
        let url = absolute(chapter.url)
        let doc = try await document(url)
        return doc.all("div#page img.page-image").enumerated().map {
            PageItem(index: $0.offset, imageURL: $0.element.absURL("src"), referer: baseURL + "/")
        }
    }

    func imageHeaders(for page: PageItem) -> [String: String] {
        ["Referer": baseURL + "/", "User-Agent": Self.imageUserAgent]
    }
}
