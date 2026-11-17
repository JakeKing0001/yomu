import Foundation
import SwiftSoup

enum SourceError: LocalizedError {
    case invalidURL(String)
    case http(Int, URL)
    case cloudflare(URL)
    case parse(String)
    case sourceUnavailable
    case badResponse

    var errorDescription: String? {
        switch self {
        case .invalidURL(let u): return "Indirizzo non valido: \(u)"
        case .http(let code, _): return "Errore HTTP \(code)"
        case .cloudflare: return "Il sito è protetto da Cloudflare. Aprilo in WebView per superare il controllo, poi riprova."
        case .parse(let what): return "Impossibile leggere la pagina: \(what)"
        case .sourceUnavailable: return "Fonte non disponibile: installa l'estensione corrispondente."
        case .badResponse: return "Risposta non valida dal server"
        }
    }

    var cloudflareURL: URL? {
        if case .cloudflare(let u) = self { return u }
        return nil
    }
}

final class HTTPClient {
    static let shared = HTTPClient()

    static let defaultUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1"

    static var userAgent: String {
        let custom = UserDefaults.standard.string(forKey: Prefs.userAgent) ?? ""
        return custom.isEmpty ? defaultUserAgent : custom
    }

    let session: URLSession

    private init() {
        let config = URLSessionConfiguration.default
        config.httpCookieStorage = .shared
        config.httpShouldSetCookies = true
        config.httpCookieAcceptPolicy = .always
        config.timeoutIntervalForRequest = 30
        config.urlCache = URLCache(memoryCapacity: 16 * 1024 * 1024, diskCapacity: 256 * 1024 * 1024)
        config.requestCachePolicy = .useProtocolCachePolicy
        session = URLSession(configuration: config)
    }

    func request(
        _ urlString: String,
        method: String = "GET",
        headers: [String: String] = [:],
        form: [(String, String)]? = nil,
        body: Data? = nil
    ) async throws -> (Data, HTTPURLResponse) {
        guard let url = URL(string: urlString) else { throw SourceError.invalidURL(urlString) }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7", forHTTPHeaderField: "Accept-Language")
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        if let form {
            req.httpMethod = "POST"
            req.setValue("application/x-www-form-urlencoded; charset=UTF-8", forHTTPHeaderField: "Content-Type")
            req.httpBody = Self.encodeForm(form)
        } else if let body {
            req.httpBody = body
        }
        let (data, response) = try await session.data(for: req)
        guard let http = response as? HTTPURLResponse else { throw SourceError.badResponse }
        if http.statusCode >= 400 {
            if [403, 429, 503].contains(http.statusCode), Self.isCloudflare(http, data) {
                throw SourceError.cloudflare(url)
            }
            throw SourceError.http(http.statusCode, url)
        }
        return (data, http)
    }

    func string(
        _ url: String,
        method: String = "GET",
        headers: [String: String] = [:],
        form: [(String, String)]? = nil
    ) async throws -> (String, URL) {
        let (data, response) = try await request(url, method: method, headers: headers, form: form)
        let text = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
        return (text, response.url ?? URL(string: url)!)
    }

    func document(
        _ url: String,
        method: String = "GET",
        headers: [String: String] = [:],
        form: [(String, String)]? = nil
    ) async throws -> Document {
        let (html, finalURL) = try await string(url, method: method, headers: headers, form: form)
        return try SwiftSoup.parse(html, finalURL.absoluteString)
    }

    func json<T: Decodable>(_ type: T.Type, _ url: String, headers: [String: String] = [:]) async throws -> T {
        var h = headers
        h["Accept"] = "application/json"
        let (data, _) = try await request(url, headers: h)
        return try JSONDecoder().decode(T.self, from: data)
    }

    static func encodeForm(_ form: [(String, String)]) -> Data {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let body = form.map { key, value in
            let k = key.addingPercentEncoding(withAllowedCharacters: allowed) ?? key
            let v = value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
            return "\(k)=\(v)"
        }.joined(separator: "&")
        return Data(body.utf8)
    }

    static func isCloudflare(_ response: HTTPURLResponse, _ data: Data) -> Bool {
        let server = (response.value(forHTTPHeaderField: "Server") ?? "").lowercased()
        if server.contains("cloudflare") || server.contains("ddos-guard") { return true }
        let sample = String(decoding: data.prefix(4096), as: UTF8.self)
        return sample.contains("cf-browser-verification") || sample.contains("challenge-platform") || sample.contains("Just a moment")
    }

    static func clearCookies() {
        HTTPCookieStorage.shared.cookies?.forEach { HTTPCookieStorage.shared.deleteCookie($0) }
    }

    static func encodePath(_ s: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }

    static func encodeQuery(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }
}

extension String {
    /// Rimuove schema e dominio, lasciando percorso + query (come setUrlWithoutDomain di Mihon).
    var pathAndQuery: String {
        guard let comps = URLComponents(string: self), comps.host != nil else { return self }
        var result = comps.percentEncodedPath
        if result.isEmpty { result = "/" }
        if let q = comps.percentEncodedQuery, !q.isEmpty { result += "?" + q }
        return result
    }

    func absolute(base: String) -> String {
        if hasPrefix("http://") || hasPrefix("https://") { return self }
        if hasPrefix("//") { return "https:" + self }
        if let b = URL(string: base), let u = URL(string: self, relativeTo: b) { return u.absoluteString }
        let trimmedBase = base.hasSuffix("/") ? String(base.dropLast()) : base
        return trimmedBase + (hasPrefix("/") ? self : "/" + self)
    }

    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }

    var nilIfEmpty: String? {
        let t = trimmed
        return t.isEmpty ? nil : t
    }

    func substring(after marker: String) -> String? {
        guard let r = range(of: marker) else { return nil }
        return String(self[r.upperBound...])
    }

    func substring(before marker: String) -> String {
        guard let r = range(of: marker) else { return self }
        return String(self[..<r.lowerBound])
    }
}
