import Foundation

/// Un'estensione così come descritta dall'indice del repository (index.pb o index.min.json).
struct RepoExtension: Codable, Hashable, Identifiable {
    let name: String
    let pkg: String
    let version: String
    /// 1 = sicura, 2 = mista, 3 = NSFW (ContentWarning di Keiyoushi).
    let contentWarning: Int
    let iconURL: String?
    let sources: [SourceInfo]
    let repoURL: String

    var id: String { pkg }

    /// Lingua dal nome del pacchetto: eu.kanade.tachiyomi.extension.<lang>.<nome>
    var lang: String {
        let parts = pkg.split(separator: ".")
        return parts.count >= 2 ? String(parts[parts.count - 2]) : "all"
    }

    /// Chiave usata in themes.json, es. "it.mangaworld".
    var themeKey: String {
        let parts = pkg.split(separator: ".")
        return parts.suffix(2).joined(separator: ".")
    }

    var isNSFW: Bool { contentWarning >= 3 }
}

struct RepoIndex {
    var name: String
    var extensions: [RepoExtension]
}

enum RepoParserError: LocalizedError {
    case invalid(String)
    var errorDescription: String? {
        if case .invalid(let s) = self { return "Indice del repository non valido: \(s)" }
        return nil
    }
}

enum RepoParser {
    static func parse(_ raw: Data, repoURL: String) throws -> RepoIndex {
        let data = try gunzipIfNeeded(raw)
        guard let first = data.first(where: { !($0 == 0x20 || $0 == 0x0A || $0 == 0x0D || $0 == 0x09) }) else {
            throw RepoParserError.invalid("file vuoto")
        }
        if first == UInt8(ascii: "[") || first == UInt8(ascii: "{") {
            return try parseJSON(data, repoURL: repoURL)
        }
        return try parseProtobuf([UInt8](data), repoURL: repoURL)
    }

    // MARK: gzip

    static func gunzipIfNeeded(_ data: Data) throws -> Data {
        let b = [UInt8](data)
        guard b.count > 18, b[0] == 0x1f, b[1] == 0x8b else { return data }
        let flags = b[3]
        var pos = 10
        if flags & 0x04 != 0 {
            let xlen = Int(b[pos]) | (Int(b[pos + 1]) << 8)
            pos += 2 + xlen
        }
        if flags & 0x08 != 0 { while pos < b.count && b[pos] != 0 { pos += 1 }; pos += 1 }
        if flags & 0x10 != 0 { while pos < b.count && b[pos] != 0 { pos += 1 }; pos += 1 }
        if flags & 0x02 != 0 { pos += 2 }
        guard pos < b.count - 8 else { throw RepoParserError.invalid("gzip troncato") }
        let deflate = Data(b[pos..<(b.count - 8)])
        // NSData .zlib di Apple corrisponde a DEFLATE "raw", cioè il contenuto di un file gzip.
        return try (deflate as NSData).decompressed(using: .zlib) as Data
    }

    // MARK: Protobuf

    private enum Field {
        case varint(UInt64)
        case bytes(ArraySlice<UInt8>)

        var string: String {
            if case .bytes(let b) = self { return String(decoding: b, as: UTF8.self) }
            return ""
        }
        var uint: UInt64 {
            if case .varint(let v) = self { return v }
            return 0
        }
        var slice: ArraySlice<UInt8> {
            if case .bytes(let b) = self { return b }
            return []
        }
    }

    private static func fields(_ bytes: ArraySlice<UInt8>, _ handle: (Int, Field) throws -> Void) throws {
        var i = bytes.startIndex
        func varint() throws -> UInt64 {
            var result: UInt64 = 0
            var shift: UInt64 = 0
            while true {
                guard i < bytes.endIndex else { throw RepoParserError.invalid("varint troncato") }
                let byte = bytes[i]
                i += 1
                result |= UInt64(byte & 0x7f) << shift
                if byte < 0x80 { return result }
                shift += 7
                if shift > 63 { throw RepoParserError.invalid("varint troppo lungo") }
            }
        }
        while i < bytes.endIndex {
            let key = try varint()
            let field = Int(key >> 3)
            switch key & 7 {
            case 0:
                try handle(field, .varint(try varint()))
            case 2:
                let length = Int(try varint())
                guard length >= 0, i + length <= bytes.endIndex else { throw RepoParserError.invalid("lunghezza") }
                try handle(field, .bytes(bytes[i..<(i + length)]))
                i += length
            case 1:
                i += 8
            case 5:
                i += 4
            default:
                throw RepoParserError.invalid("wire type \(key & 7)")
            }
        }
    }

    private static func parseProtobuf(_ bytes: [UInt8], repoURL: String) throws -> RepoIndex {
        var name = "Repository"
        var extensions: [RepoExtension] = []
        try fields(bytes[...]) { field, value in
            switch field {
            case 1: name = value.string
            case 101:
                try fields(value.slice) { f, v in
                    if f == 1, let ext = try parseExtension(v.slice, repoURL: repoURL) { extensions.append(ext) }
                }
            default: break
            }
        }
        if extensions.isEmpty { throw RepoParserError.invalid("nessuna estensione") }
        return RepoIndex(name: name, extensions: extensions)
    }

    private static func parseExtension(_ bytes: ArraySlice<UInt8>, repoURL: String) throws -> RepoExtension? {
        var name = "", pkg = "", version = "", icon: String?
        var warning = 1
        var sources: [SourceInfo] = []
        try fields(bytes) { field, value in
            switch field {
            case 1: name = value.string
            case 2: pkg = value.string
            case 3:
                try fields(value.slice) { f, v in if f == 2 { icon = v.string } }
            case 6: version = value.string
            case 7: warning = Int(value.uint)
            case 8:
                var id: UInt64 = 0
                var sName = "", lang = "", base = ""
                try fields(value.slice) { f, v in
                    switch f {
                    case 1: id = v.uint
                    case 2: sName = v.string
                    case 3: lang = v.string
                    case 4: base = v.string
                    default: break
                    }
                }
                sources.append(SourceInfo(id: String(id), name: sName, lang: lang, baseURL: trimSlash(base)))
            default: break
            }
        }
        guard !pkg.isEmpty else { return nil }
        return RepoExtension(name: name, pkg: pkg, version: version, contentWarning: warning, iconURL: icon, sources: sources, repoURL: repoURL)
    }

    // MARK: JSON (formato index.min.json di Tachiyomi/Mihon)

    private struct JSONExtension: Decodable {
        let name: String
        let pkg: String
        let version: String?
        let nsfw: Int?
        let sources: [JSONSource]?
    }

    private struct JSONSource: Decodable {
        let name: String
        let lang: String
        let id: String
        let baseUrl: String

        enum CodingKeys: String, CodingKey { case name, lang, id, baseUrl }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            name = try c.decode(String.self, forKey: .name)
            lang = try c.decode(String.self, forKey: .lang)
            baseUrl = (try? c.decode(String.self, forKey: .baseUrl)) ?? ""
            if let s = try? c.decode(String.self, forKey: .id) {
                id = s
            } else if let n = try? c.decode(UInt64.self, forKey: .id) {
                id = String(n)
            } else {
                id = String((try? c.decode(Int64.self, forKey: .id)) ?? 0)
            }
        }
    }

    private static func parseJSON(_ data: Data, repoURL: String) throws -> RepoIndex {
        let list = try JSONDecoder().decode([JSONExtension].self, from: data)
        let base = repoURL.components(separatedBy: "/").dropLast().joined(separator: "/")
        let exts = list.map { e in
            RepoExtension(
                name: e.name.replacingOccurrences(of: "Tachiyomi: ", with: ""),
                pkg: e.pkg,
                version: e.version ?? "",
                contentWarning: (e.nsfw ?? 0) == 1 ? 3 : 1,
                iconURL: "\(base)/icon/\(e.pkg).png",
                sources: (e.sources ?? []).map { SourceInfo(id: $0.id, name: $0.name, lang: $0.lang, baseURL: trimSlash($0.baseUrl)) },
                repoURL: repoURL
            )
        }
        return RepoIndex(name: URL(string: repoURL)?.host ?? "Repository", extensions: exts)
    }

    private static func trimSlash(_ s: String) -> String {
        var r = s.trimmed
        while r.hasSuffix("/") { r.removeLast() }
        return r
    }

    /// Accetta l'URL dell'indice o la cartella che lo contiene.
    static func candidateURLs(for input: String) -> [String] {
        var url = input.trimmed
        if !url.hasPrefix("http") { url = "https://" + url }
        if url.hasSuffix(".pb") || url.hasSuffix(".json") { return [url] }
        while url.hasSuffix("/") { url.removeLast() }
        return [url + "/index.pb", url + "/index.min.json"]
    }
}
