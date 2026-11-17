import Foundation
import SwiftSoup

extension Element {
    func first(_ css: String) -> Element? { (try? select(css))?.first() }

    func all(_ css: String) -> [Element] { (try? select(css))?.array() ?? [] }

    func attrValue(_ key: String) -> String { ((try? attr(key)) ?? "").trimmed }

    func absURL(_ key: String) -> String {
        let value = ((try? absUrl(key)) ?? "").trimmed
        if !value.isEmpty { return value }
        return attrValue(key)
    }

    func has(_ key: String) -> Bool { hasAttr(key) }

    var textValue: String { ((try? text()) ?? "").trimmed }

    var ownTextValue: String { ownText().trimmed }

    var htmlValue: String { (try? html()) ?? "" }

    /// Immagine con i vari attributi lazy-load usati dai temi WordPress.
    func imageURL() -> String? {
        let keys = ["data-src", "data-lazy-src", "data-lzl-src", "data-cfsrc", "data-manga-src", "data-original"]
        for key in keys where has(key) {
            let v = absURL(key)
            if !v.isEmpty { return v }
        }
        if has("srcset"), let best = Element.bestFromSrcSet(attrValue("srcset")) {
            return best.absolute(base: getBaseUri())
        }
        let src = absURL("src")
        return src.isEmpty ? nil : src
    }

    static func bestFromSrcSet(_ srcset: String) -> String? {
        var best: (String, Double)?
        for candidate in srcset.split(separator: ",") {
            let parts = String(candidate).trimmed.split(whereSeparator: { $0 == " " || $0 == "\n" })
            guard let url = parts.first.map(String.init), !url.isEmpty else { continue }
            var weight = 0.0
            if parts.count > 1 {
                weight = Double(parts[1].dropLast()) ?? 0
            }
            if best == nil || weight > best!.1 { best = (url, weight) }
        }
        return best?.0
    }
}

enum ChapterRecognition {
    private static let keyword = try! NSRegularExpression(
        pattern: #"(?i)(?:chapter|chap|ch|capitolo|cap|capítulo|capitulo|chapitre|kapitel|episodio|episode|ep|bölüm|bab|chương|глава|第)\s*\.?\s*(\d+(?:[.,]\d+)?)"#
    )
    private static let anyNumber = try! NSRegularExpression(pattern: #"(\d+(?:\.\d+)?)"#)

    static func number(from name: String) -> Double {
        let range = NSRange(name.startIndex..., in: name)
        for regex in [keyword, anyNumber] {
            if let m = regex.firstMatch(in: name, range: range),
               let r = Range(m.range(at: 1), in: name),
               let v = Double(name[r].replacingOccurrences(of: ",", with: ".")) {
                return v
            }
        }
        return -1
    }
}

enum DateParser {
    private static var cache: [String: DateFormatter] = [:]
    private static let lock = NSLock()

    private static func formatter(_ format: String, _ locale: String) -> DateFormatter {
        lock.lock(); defer { lock.unlock() }
        let key = format + "|" + locale
        if let f = cache[key] { return f }
        let f = DateFormatter()
        f.dateFormat = format
        f.locale = Locale(identifier: locale)
        f.timeZone = TimeZone(identifier: "UTC")
        cache[key] = f
        return f
    }

    static func parse(_ raw: String?, format: String = "MMMM d, yyyy", lang: String = "en") -> Date? {
        guard let s = raw?.trimmed, !s.isEmpty else { return nil }
        let locale = lang == "all" ? "en_US" : lang
        let formats = [format, "MMMM d, yyyy", "MMM d, yyyy", "d MMMM yyyy", "dd MMMM yyyy", "dd/MM/yyyy", "yyyy-MM-dd", "MM/dd/yyyy", "dd-MM-yyyy", "dd.MM.yyyy"]
        for f in formats {
            for loc in [locale, "en_US"] {
                if let d = formatter(f, loc).date(from: s) { return d }
            }
        }
        if let d = ISO8601DateFormatter().date(from: s) { return d }
        return relative(s.lowercased())
    }

    private static func relative(_ s: String) -> Date? {
        let cal = Calendar.current
        let now = Date()
        let today = cal.startOfDay(for: now)
        if ["today", "oggi", "hoje", "hoy", "aujourd"].contains(where: { s.hasPrefix($0) }) { return today }
        if ["yesterday", "ieri", "ontem", "ayer", "hier"].contains(where: { s.hasPrefix($0) }) {
            return cal.date(byAdding: .day, value: -1, to: today)
        }
        let tokens = s.components(separatedBy: CharacterSet.letters.inverted).filter { !$0.isEmpty }
        guard let numberMatch = s.range(of: #"\d+"#, options: .regularExpression),
              let amount = Int(s[numberMatch]) else {
            if tokens.contains(where: { ["just", "now", "ora", "adesso", "agora"].contains($0) }) { return now }
            return nil
        }
        func any(_ words: [String]) -> Bool {
            tokens.contains { t in words.contains { w in w.count <= 3 ? t == w : t.hasPrefix(w) } }
        }
        let comp: Calendar.Component?
        if any(["year", "anno", "anni", "año", "ano", "anos", "an", "ans", "năm", "yıl", "tahun"]) { comp = .year }
        else if any(["month", "mese", "mesi", "mes", "meses", "mois", "bulan", "tháng", "ay"]) { comp = .month }
        else if any(["week", "settiman", "semana", "semaine", "minggu", "tuần", "hafta"]) { comp = .weekOfYear }
        else if any(["day", "giorn", "día", "dia", "dias", "días", "jour", "hari", "ngày", "gün"]) { comp = .day }
        else if any(["hour", "ora", "ore", "hora", "horas", "heure", "jam", "giờ", "saat"]) { comp = .hour }
        else if any(["minut", "min", "mins", "menit", "phút", "dakika"]) { comp = .minute }
        else if any(["second", "secondi", "segundo", "sec", "detik", "giây", "saniye"]) { comp = .second }
        else { comp = nil }
        guard let comp else { return nil }
        return cal.date(byAdding: comp, value: -amount, to: now)
    }
}
