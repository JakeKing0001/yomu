import Foundation
import SwiftSoup

/// Porting in Swift del tema MangaThemesia (ex WPMangaStream / WPMangaReader).
final class MangaThemesiaSource: MangaSource {
    let info: SourceInfo
    private let directory: String
    private let datePattern: String

    private let listSelector = ".utao .uta .imgu, .listupd .bs .bsx, .listo .bs .bsx"
    private let nextSelector = "div.pagination .next, div.hpage .r"
    private let detailsSelector = "div.bigcontent, div.animefull, div.main-info, div.postbody"
    private let titleSelector = ".entry-title, .ts-breadcrumb li:last-child span"
    private let descriptionSelector = ".desc, .entry-content[itemprop=description]"
    private let genreSelector = "div.gnr a, .mgen a, .seriestugenre a, span:contains(genre)"
    private let thumbSelector = ".infomanga > div[itemprop=image] img, .thumb img"
    private let chapterSelector = "div.bxcl li, div.cl li, #chapterlist li, ul li:has(div.chbox):has(div.eph-num)"

    init(info: SourceInfo, theme: ThemeInfo) {
        self.info = info
        self.directory = theme.dir ?? "/manga"
        self.datePattern = theme.df ?? "MMMM d, yyyy"
    }

    private static func selector(_ template: String, _ words: [String]) -> String {
        words.map { template.replacingOccurrences(of: "%s", with: $0) }.joined(separator: ", ")
    }

    private let authorSelector = MangaThemesiaSource.selector(
        ".infotable tr:contains(%s) td:last-child, .tsinfo .imptdt:contains(%s) i, .fmed b:contains(%s)+span, span:contains(%s)",
        ["Author", "Auteur", "autor", "autore", "المؤلف", "Mangaka", "seniman", "Pengarang", "Yazar", "ผู้แต่ง"]
    )
    private let artistSelector = MangaThemesiaSource.selector(
        ".infotable tr:contains(%s) td:last-child, .tsinfo .imptdt:contains(%s) i, .fmed b:contains(%s)+span, span:contains(%s)",
        ["artist", "Artiste", "Artista", "الرسام", "Çizer", "นักวาด"]
    )
    private let statusSelector = MangaThemesiaSource.selector(
        ".infotable tr:contains(%s) td:last-child, .tsinfo .imptdt:contains(%s) i, .fmed b:contains(%s)+span span:contains(%s)",
        ["status", "Statut", "Durum", "Estado", "الحالة", "สถานะ", "stato"]
    )
    private let typeSelector = MangaThemesiaSource.selector(
        ".infotable tr:contains(%s) td:last-child, .tsinfo .imptdt:contains(%s) i, .tsinfo .imptdt:contains(%s) a, .fmed b:contains(%s)+span, span:contains(%s) a",
        ["type", "tipo", "ประเภท", "النوع", "tipe", "Türü"]
    )

    // MARK: Liste

    func popular(page: Int) async throws -> MangasPage { try await list(page: page, query: "", order: "popular") }
    func latest(page: Int) async throws -> MangasPage { try await list(page: page, query: "", order: "update") }
    func search(query: String, page: Int) async throws -> MangasPage { try await list(page: page, query: query, order: "") }

    private func list(page: Int, query: String, order: String) async throws -> MangasPage {
        var params: [String] = []
        if !query.isEmpty { params.append("title=\(HTTPClient.encodeQuery(query))") }
        params.append("page=\(page)")
        if !order.isEmpty { params.append("order=\(order)") }
        let url = "\(baseURL)\(directory)/?\(params.joined(separator: "&"))"
        let doc = try await HTTPClient.shared.document(url, headers: ["Referer": baseURL + "/"])
        return parseList(doc)
    }

    private func parseList(_ doc: Document) -> MangasPage {
        let mangas: [SManga] = doc.all(listSelector).compactMap { element in
            guard let link = element.first("a") else { return nil }
            let href = link.absURL("href")
            let title = link.attrValue("title").nilIfEmpty ?? element.first(".tt, .ntitle")?.textValue ?? link.textValue
            guard !href.isEmpty, !title.isEmpty else { return nil }
            return SManga(url: href.pathAndQuery, title: title, thumbnailURL: element.first("img")?.imageURL())
        }
        return MangasPage(mangas: mangas, hasNextPage: doc.first(nextSelector) != nil)
    }

    // MARK: Dettagli

    func details(_ manga: SManga) async throws -> (SManga, [SChapter]) {
        let doc = try await HTTPClient.shared.document(absolute(manga.url), headers: ["Referer": baseURL + "/"])
        var result = manga
        if let details = doc.first(detailsSelector) {
            if let title = details.first(titleSelector)?.textValue, !title.isEmpty { result.title = title }
            result.author = clean(details.first(authorSelector)?.ownTextValue)
            result.artist = clean(details.first(artistSelector)?.ownTextValue)
            result.description = details.first(descriptionSelector)?.textValue.nilIfEmpty
            var genres = details.all(genreSelector).map(\.textValue).filter { !$0.isEmpty }
            if let type = details.first(typeSelector)?.ownTextValue, !type.isEmpty { genres.append(type) }
            result.genres = genres.map { $0.prefix(1).uppercased() + $0.dropFirst() }
            result.status = Self.status(details.first(statusSelector)?.textValue)
            if let thumb = details.first(thumbSelector)?.imageURL() { result.thumbnailURL = thumb }
        }
        var chapters: [SChapter] = doc.all(chapterSelector).compactMap { element in
            guard let link = element.first("a") else { return nil }
            let href = link.absURL("href")
            guard !href.isEmpty else { return nil }
            let name = element.first(".lch a, .chapternum")?.textValue.nilIfEmpty ?? link.textValue
            return SChapter(
                url: href.pathAndQuery,
                name: name,
                number: ChapterRecognition.number(from: name),
                dateUpload: DateParser.parse(element.first(".chapterdate")?.textValue, format: datePattern, lang: lang)
            )
        }
        if !chapters.isEmpty, chapters[0].dateUpload == nil,
           let modified = doc.first(".listinfo time[itemprop=dateModified], .fmed:contains(update) time, span:contains(update) time")?.attrValue("datetime") {
            chapters[0].dateUpload = DateParser.parse(String(modified.prefix(10)), format: "yyyy-MM-dd")
        }
        return (result, chapters)
    }

    private func clean(_ s: String?) -> String? {
        guard let s = s?.trimmed, !s.isEmpty, !["-", "N/A", "n/a", "Unknown"].contains(s) else { return nil }
        return s
    }

    static func status(_ raw: String?) -> MangaStatus {
        guard let s = raw?.lowercased() else { return .unknown }
        if ["ongoing", "on going", "in corso", "en curso", "en cours", "em andamento", "berjalan", "devam ediyor", "publishing", "updating", "مستمرة", "连载中", "連載中"].contains(where: { s.contains($0) }) { return .ongoing }
        if ["completed", "completo", "completata", "completato", "complété", "terminé", "tamat", "tamamlandı", "finished", "finalizad", "concluído", "concluido", "完結", "已完结"].contains(where: { s.contains($0) }) { return .completed }
        if ["canceled", "cancelled", "cancellato", "cancelado", "dropped", "discontinued"].contains(where: { s.contains($0) }) { return .cancelled }
        if ["hiatus", "on hold", "in pausa", "pausado", "en pause"].contains(where: { s.contains($0) }) { return .onHiatus }
        return .unknown
    }

    // MARK: Pagine

    func pages(_ chapter: SChapter) async throws -> [PageItem] {
        let url = absolute(chapter.url)
        let doc = try await HTTPClient.shared.document(url, headers: ["Referer": baseURL + "/"])
        let html = doc.all("div#readerarea img").compactMap { $0.imageURL() }.filter { !$0.isEmpty && !$0.hasPrefix("data:") }
        if !html.isEmpty {
            return html.enumerated().map { PageItem(index: $0.offset, imageURL: $0.element, referer: baseURL + "/") }
        }
        // Alcuni siti caricano le pagine via JavaScript: ts_reader.run({... "images": [...] })
        var source = doc.htmlValue
        if let script = doc.first("script[src^=data:text/javascript;base64,dHNfcmVhZGVyLnJ1bih7]") {
            let src = script.attrValue("src")
            if let b64 = src.substring(after: "base64,"), let data = Data(base64Encoded: b64) {
                source = String(decoding: data, as: UTF8.self)
            }
        }
        let regex = try NSRegularExpression(pattern: #"["']?(?:images|imageUrls)["']?\s*[:=]\s*(\[.*?\])"#, options: [.dotMatchesLineSeparators])
        guard let match = regex.firstMatch(in: source, range: NSRange(source.startIndex..., in: source)),
              let range = Range(match.range(at: 1), in: source),
              let list = try? JSONSerialization.jsonObject(with: Data(source[range].utf8)) as? [String] else {
            return []
        }
        return list.enumerated().map {
            PageItem(index: $0.offset, imageURL: $0.element.absolute(base: baseURL), referer: baseURL + "/")
        }
    }

    func imageHeaders(for page: PageItem) -> [String: String] {
        ["Referer": baseURL + "/", "Accept": "image/avif,image/webp,image/png,image/jpeg,*/*"]
    }
}
