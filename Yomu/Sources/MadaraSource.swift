import Foundation
import SwiftSoup
import CryptoKit
import CommonCrypto

/// Porting in Swift del tema Madara (lib-multisrc/madara di Keiyoushi).
final class MadaraSource: MangaSource {
    let info: SourceInfo
    private let chapterMode: String
    private let mangaSubString: String
    private let noAjax: Bool
    private let dateFormat: String
    private let pageSize = 25

    private let archiveSelector = "div.page-item-detail, .manga__item, .c-tabs-item__content"
    private let archiveURLSelector = ".post-title a"
    private let titleSelector = "div.post-title h3, div.post-title h1, #manga-title > h1"
    private let authorSelector = "div.author-content > a, div.manga-authors > a"
    private let artistSelector = "div.artist-content > a"
    private let statusSelector = "div.summary-content, div.summary-heading:contains(Status) + div"
    private let descriptionSelector = "div.description-summary div.summary__content, div.summary_content div.post-content_item > h5 + div, div.summary_content div.manga-excerpt"
    private let thumbnailSelector = "div.summary_image img"
    private let genreSelector = "div.genres-content a"
    private let tagSelector = "div.tags-content a"
    private let chapterSelector = "li.wp-manga-chapter"
    private let pageSelector = "div.page-break, li.blocks-gallery-item, .reading-content .text-left:not(:has(.blocks-gallery-item))"

    init(info: SourceInfo, theme: ThemeInfo) {
        self.info = info
        self.chapterMode = theme.cm ?? "MangaPage"
        self.mangaSubString = theme.ms ?? "manga"
        self.noAjax = theme.na ?? false
        self.dateFormat = theme.df ?? "MMMM d, yyyy"
    }

    private var xhr: [String: String] {
        ["X-Requested-With": "XMLHttpRequest", "Referer": baseURL + "/"]
    }

    // MARK: Liste

    func popular(page: Int) async throws -> MangasPage {
        try await list(page: page, sortKey: "_wp_manga_views", order: "views", query: nil)
    }

    func latest(page: Int) async throws -> MangasPage {
        try await list(page: page, sortKey: "_latest_update", order: "latest", query: nil)
    }

    func search(query: String, page: Int) async throws -> MangasPage {
        try await list(page: page, sortKey: nil, order: "", query: query)
    }

    private func list(page: Int, sortKey: String?, order: String, query: String?) async throws -> MangasPage {
        if !noAjax {
            do {
                let result = try await ajaxList(page: page, sortKey: sortKey, query: query)
                if !result.mangas.isEmpty || page > 1 { return result }
            } catch let error as SourceError where error.cloudflareURL != nil {
                throw error
            } catch {
                // Alcuni siti disattivano admin-ajax: si ripiega sulle pagine HTML.
            }
        }
        if let query, !query.isEmpty {
            return try await htmlSearch(page: page, query: query)
        }
        return try await archivePage(page: page, order: order)
    }

    private func ajaxList(page: Int, sortKey: String?, query: String?) async throws -> MangasPage {
        var form: [(String, String)] = [
            ("action", "madara_load_more"),
            ("page", String(page - 1)),
            ("template", "madara-core/content/content-archive"),
            ("vars[paged]", "1"),
            ("vars[template]", "archive"),
            ("vars[posts_per_page]", String(pageSize)),
            ("vars[post_type]", "wp-manga"),
            ("vars[post_status]", "publish"),
            ("vars[manga_archives_item_layout]", "big_thumbnail"),
            ("vars[meta_query][0][key]", "_wp_manga_chapter_type"),
            ("vars[meta_query][0][value]", "manga"),
        ]
        if let sortKey {
            form.append(("vars[orderby]", "meta_value_num"))
            form.append(("vars[meta_key]", sortKey))
            form.append(("vars[order]", "DESC"))
        }
        if let query, !query.isEmpty {
            form.append(("vars[s]", query))
        }
        let doc = try await HTTPClient.shared.document(baseURL + "/wp-admin/admin-ajax.php", headers: xhr, form: form)
        let mangas = parseArchive(doc)
        return MangasPage(mangas: mangas, hasNextPage: mangas.count == pageSize)
    }

    private func archivePage(page: Int, order: String) async throws -> MangasPage {
        var url = "\(baseURL)/\(mangaSubString)/"
        if page > 1 { url += "page/\(page)/" }
        if !order.isEmpty { url += "?m_orderby=\(order)" }
        let doc = try await HTTPClient.shared.document(url)
        let next = doc.first("div.nav-previous, a.nextpostslink, #navigation-ajax") != nil
        return MangasPage(mangas: parseArchive(doc), hasNextPage: next)
    }

    private func htmlSearch(page: Int, query: String) async throws -> MangasPage {
        var url = baseURL + "/"
        if page > 1 { url += "page/\(page)/" }
        url += "?s=\(HTTPClient.encodeQuery(query))&post_type=wp-manga"
        let doc = try await HTTPClient.shared.document(url)
        let next = doc.first("div.nav-previous, a.nextpostslink, #navigation-ajax") != nil
        return MangasPage(mangas: parseArchive(doc), hasNextPage: next)
    }

    private func parseArchive(_ doc: Element) -> [SManga] {
        var seen = Set<String>()
        return doc.all(archiveSelector).compactMap { element in
            guard let link = element.first(archiveURLSelector) ?? element.first("a") else { return nil }
            let href = link.absURL("href")
            guard !href.isEmpty else { return nil }
            let path = href.pathAndQuery
            guard seen.insert(path).inserted else { return nil }
            let title = link.textValue.isEmpty ? link.attrValue("title") : link.textValue
            guard !title.isEmpty else { return nil }
            return SManga(url: path, title: title, thumbnailURL: element.first("img")?.imageURL())
        }
    }

    // MARK: Dettagli

    func details(_ manga: SManga) async throws -> (SManga, [SChapter]) {
        let doc = try await HTTPClient.shared.document(absolute(manga.url), headers: ["Referer": baseURL + "/"])
        let mangaPath = ((try? doc.location()) ?? absolute(manga.url)).pathAndQuery
        var result = manga
        if let title = doc.first(titleSelector)?.ownTextValue, !title.isEmpty { result.title = title }
        result.author = doc.all(authorSelector).map(\.textValue).filter { !isUpdating($0) }.joined(separator: ", ").nilIfEmpty
        result.artist = doc.all(artistSelector).map(\.textValue).filter { !isUpdating($0) }.joined(separator: ", ").nilIfEmpty
        if let desc = doc.first(descriptionSelector) {
            let paragraphs = desc.all("p").map(\.textValue).filter { !$0.isEmpty }
            result.description = paragraphs.isEmpty ? desc.textValue : paragraphs.joined(separator: "\n\n")
        }
        if let thumb = doc.first(thumbnailSelector)?.imageURL() { result.thumbnailURL = thumb }
        result.status = MadaraSource.status(doc.all(statusSelector).last?.textValue ?? "")
        var genres = doc.all(genreSelector).map(\.textValue) + doc.all(tagSelector).map(\.textValue)
        var seenGenres = Set<String>()
        genres = genres.filter { !$0.isEmpty && seenGenres.insert($0.lowercased()).inserted }
        result.genres = genres

        let chapters = try await fetchChapters(doc: doc, mangaPath: mangaPath)
        return (result, chapters)
    }

    private func fetchChapters(doc: Document, mangaPath: String) async throws -> [SChapter] {
        var modes = [chapterMode]
        for fallback in ["MangaPage", "MangaAjax", "AdminAjax"] where !modes.contains(fallback) {
            modes.append(fallback)
        }
        var lastError: Error?
        for mode in modes {
            do {
                let chapters = try await fetchChapters(mode: mode, doc: doc, mangaPath: mangaPath)
                if !chapters.isEmpty { return chapters }
            } catch {
                lastError = error
            }
        }
        if let lastError, chapterMode != "MangaPage" { throw lastError }
        return []
    }

    private func fetchChapters(mode: String, doc: Document, mangaPath: String) async throws -> [SChapter] {
        let path = mangaPath.substring(before: "?")
        let ajaxURL = baseURL + (path.hasSuffix("/") ? String(path.dropLast()) : path) + "/ajax/chapters/"
        var headers = xhr
        headers["Referer"] = absolute(mangaPath)
        switch mode {
        case "MangaAjax":
            let d = try await HTTPClient.shared.document(ajaxURL, headers: headers, form: [])
            return parseChapters(d)
        case "MangaAjaxPaginated":
            var result: [SChapter] = []
            var lastURL: String?
            for page in 1...200 {
                let d: Document
                do {
                    d = try await HTTPClient.shared.document("\(ajaxURL)?t=\(page)", headers: headers, form: [])
                } catch SourceError.http(404, _) {
                    break
                }
                let chapters = parseChapters(d)
                if chapters.isEmpty || chapters.last?.url == lastURL { break }
                result += chapters
                lastURL = chapters.last?.url
            }
            return result
        case "AdminAjax":
            guard let id = mangaId(doc) else { return [] }
            let d = try await HTTPClient.shared.document(
                baseURL + "/wp-admin/admin-ajax.php", headers: headers,
                form: [("action", "manga_get_chapters"), ("manga", id)]
            )
            return parseChapters(d)
        case "MangaAjaxQuery":
            let slug = path.split(separator: "/").last.map(String.init) ?? ""
            let d = try await HTTPClient.shared.document(
                baseURL + "/index.php", headers: headers,
                form: [("manga-core", slug), ("manga_ajax", "1"), ("maction", "get_chapters")]
            )
            return parseChapters(d)
        default:
            return parseChapters(doc)
        }
    }

    private func parseChapters(_ doc: Element) -> [SChapter] {
        doc.all(chapterSelector).compactMap { element in
            guard let link = element.first("a") else { return nil }
            let href = link.absURL("href")
            guard !href.isEmpty else { return nil }
            let name = link.textValue
            let rawDate = element.first("img:not(.thumb)")?.attrValue("alt").nilIfEmpty
                ?? element.first("span a")?.attrValue("title").nilIfEmpty
                ?? element.first("span.chapter-release-date")?.textValue
            return SChapter(
                url: href.pathAndQuery,
                name: name.isEmpty ? href : name,
                number: ChapterRecognition.number(from: name),
                dateUpload: DateParser.parse(rawDate, format: dateFormat, lang: lang)
            )
        }
    }

    private func mangaId(_ doc: Document) -> String? {
        if let v = doc.first("[id^=manga-chapters-holder]")?.attrValue("data-id"), !v.isEmpty { return v }
        if let v = doc.first("input.rating-post-id")?.attrValue("value"), !v.isEmpty { return v }
        if let v = doc.first("a[data-post]")?.attrValue("data-post"), !v.isEmpty { return v }
        if let href = doc.first("link[rel=shortlink]")?.attrValue("href"), let id = href.substring(after: "?p=") {
            return id.substring(before: "&").nilIfEmpty
        }
        return nil
    }

    // MARK: Pagine

    func pages(_ chapter: SChapter) async throws -> [PageItem] {
        let url = absolute(chapter.url)
        var doc = try await HTTPClient.shared.document(url, headers: ["Referer": baseURL + "/"])
        if doc.first("#single-pager") != nil {
            let listURL = url + (url.contains("?") ? "&" : "?") + "style=list"
            doc = try await HTTPClient.shared.document(listURL, headers: ["Referer": baseURL + "/"])
        }
        if let protector = doc.first("#chapter-protector-data") {
            let images = try decryptProtector(protector)
            return images.enumerated().map { PageItem(index: $0.offset, imageURL: $0.element.trimmed, referer: url) }
        }
        let images = doc.all(pageSelector).compactMap { $0.first("img")?.imageURL() }
        return images.enumerated().map { PageItem(index: $0.offset, imageURL: $0.element, referer: url) }
    }

    private func decryptProtector(_ element: Element) throws -> [String] {
        let src = element.attrValue("src")
        let script: String
        if src.hasPrefix("data:text/javascript;base64,"),
           let data = Data(base64Encoded: String(src.dropFirst("data:text/javascript;base64,".count))) {
            script = String(decoding: data, as: UTF8.self)
        } else {
            script = element.htmlValue
        }
        guard let password = script.substring(after: "wpmangaprotectornonce='")?.substring(before: "';"),
              let encrypted = script.substring(after: "chapter_data='")?.substring(before: "';").replacingOccurrences(of: "\\/", with: "/"),
              let json = try JSONSerialization.jsonObject(with: Data(encrypted.utf8)) as? [String: Any],
              let ct = json["ct"] as? String, let saltHex = json["s"] as? String,
              let cipher = Data(base64Encoded: ct) else {
            throw SourceError.parse("dati protetti del capitolo")
        }
        let salt = Data(hex: saltHex)
        var keyIV = Data()
        var previous = Data()
        while keyIV.count < 48 {
            previous = Data(Insecure.MD5.hash(data: previous + Data(password.utf8) + salt))
            keyIV.append(previous)
        }
        let plain = try AESCBC.decrypt(cipher, key: keyIV.prefix(32), iv: keyIV.subdata(in: 32..<48))
        // Il risultato è una stringa JSON che contiene a sua volta un array JSON.
        let outer = try JSONSerialization.jsonObject(with: plain, options: [.fragmentsAllowed])
        if let inner = outer as? String,
           let list = try JSONSerialization.jsonObject(with: Data(inner.utf8)) as? [String] {
            return list
        }
        if let list = outer as? [String] { return list }
        throw SourceError.parse("lista immagini")
    }

    // MARK: Utilità

    private func isUpdating(_ s: String) -> Bool {
        let l = s.lowercased()
        return l.contains("updating") || l.contains("atualizando")
    }

    static func status(_ raw: String) -> MangaStatus {
        let s = raw.lowercased()
        let completed = ["completed", "completo", "completato", "completado", "concluído", "concluido", "finalizado", "achevé", "terminé", "hoàn thành", "مكتملة", "مكتمل", "已完结", "tamamlandı", "завершено", "complété", "finito"]
        let ongoing = ["ongoing", "on going", "updating", "in corso", "in arrivo", "продолжается", "em lançamento", "em andamento", "en cours", "ativo", "lançando", "đang tiến hành", "devam ediyor", "مستمرة", "مستمر", "en curso", "emision", "publicandose", "publicándose", "连载中", "em postagem"]
        let hiatus = ["on hold", "hiatus", "in pausa", "pausado", "en espera", "durduruldu", "beklemede", "متوقف", "en pause", "заморожено"]
        let cancelled = ["canceled", "cancelled", "cancellato", "cancelado", "iptal edildi", "đã hủy", "ملغي", "abandonné", "заброшено"]
        if completed.contains(where: { s.contains($0) }) { return .completed }
        if ongoing.contains(where: { s.contains($0) }) { return .ongoing }
        if hiatus.contains(where: { s.contains($0) }) { return .onHiatus }
        if cancelled.contains(where: { s.contains($0) }) { return .cancelled }
        return .unknown
    }
}

extension Data {
    init(hex: String) {
        var data = Data()
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2, limitedBy: hex.endIndex) ?? hex.endIndex
            if let byte = UInt8(hex[index..<next], radix: 16) { data.append(byte) }
            index = next
        }
        self = data
    }
}

enum AESCBC {
    static func decrypt(_ data: Data, key: Data, iv: Data) throws -> Data {
        let keyBytes = [UInt8](key)
        let ivBytes = [UInt8](iv)
        let input = [UInt8](data)
        var output = [UInt8](repeating: 0, count: input.count + kCCBlockSizeAES128)
        var moved = 0
        let status = CCCrypt(
            CCOperation(kCCDecrypt),
            CCAlgorithm(kCCAlgorithmAES),
            CCOptions(kCCOptionPKCS7Padding),
            keyBytes, keyBytes.count,
            ivBytes,
            input, input.count,
            &output, output.count,
            &moved
        )
        guard status == kCCSuccess else { throw SourceError.parse("decrittazione AES") }
        return Data(output.prefix(moved))
    }
}
