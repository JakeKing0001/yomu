import Foundation
import SwiftData

/// Parametri di avvio usati solo per generare gli screenshot nel simulatore
/// (es. `-demoTab 2`, `-demoScreen detail`). Nelle build Release sono ignorati.
enum Demo {
    static func int(_ key: String) -> Int {
        #if DEBUG
        return UserDefaults.standard.integer(forKey: key)
        #else
        return 0
        #endif
    }

    static func string(_ key: String) -> String {
        #if DEBUG
        return UserDefaults.standard.string(forKey: key) ?? ""
        #else
        return ""
        #endif
    }

    static var screen: String { string("demoScreen") }
    static var shouldSeed: Bool { string("demoSeed") == "YES" }
}

#if DEBUG
/// Riempie l'app con dati reali (repository Keiyoushi + manga da fonti supportate).
@MainActor
enum DemoSeeder {
    static func run(context: ModelContext, extensions: ExtensionStore) async {
        let existing = (try? context.fetch(FetchDescriptor<MangaEntity>())) ?? []
        guard existing.isEmpty else { return }
        print("[demo] aggiungo repository")
        try? await extensions.addRepo("https://github.com/keiyoushi/extensions/raw/repo/index.pb")
        let preferred = ["it.mangaworld", "it.lupiteam", "it.hastateam", "it.shinobiscans", "it.walpurgisscan",
                         "it.phoenixscans", "it.tuttoanimemanga", "it.animeshinigami"]
        for key in preferred {
            if let ext = extensions.available.first(where: { $0.themeKey == key }) { extensions.install(ext) }
        }
        var enCount = 0
        for ext in extensions.available where ext.lang == "en" && extensions.isSupported(ext) && !ext.isNSFW && enCount < 6 {
            extensions.install(ext)
            enCount += 1
        }
        print("[demo] installate \(extensions.installed.count) estensioni")

        let categories = [CategoryEntity(name: "In corso", order: 0), CategoryEntity(name: "Preferiti", order: 1)]
        categories.forEach { context.insert($0) }

        var added: [MangaEntity] = []
        for key in preferred {
            guard added.count < 12,
                  let ext = extensions.installed.values.first(where: { $0.themeKey == key }),
                  let info = ext.sources.first,
                  let source = extensions.source(id: info.id) else { continue }
            do {
                let page = try await source.popular(page: 1)
                print("[demo] \(info.name): \(page.mangas.count) manga")
                for m in page.mangas.prefix(12 - added.count) {
                    let entity = MangaRepository.upsert(m, source: info, in: context)
                    entity.inLibrary = true
                    entity.dateAdded = Date()
                    added.append(entity)
                }
            } catch {
                print("[demo] \(info.name) errore: \(error.localizedDescription)")
            }
        }
        for (i, manga) in added.enumerated() {
            if i % 3 == 1 { manga.categoryIDs = [categories[0].id] }
        }
        let now = Date()
        for (i, manga) in added.prefix(5).enumerated() {
            guard let source = extensions.source(id: manga.sourceId) else { continue }
            do {
                _ = try await MangaRepository.refresh(manga, source: source, in: context)
            } catch {
                print("[demo] dettagli \(manga.title): \(error.localizedDescription)")
                continue
            }
            let chapters = manga.chapters.sorted { $0.sourceOrder > $1.sourceOrder }
            let readCount = chapters.count * (i + 1) / 7
            for (j, c) in chapters.enumerated() where j < readCount { c.read = true }
            if readCount > 0 {
                chapters[readCount - 1].lastReadAt = now.addingTimeInterval(-Double(i) * 60 * 60 * 20)
            }
            for c in chapters.suffix(2) {
                c.isNew = true
                c.dateFetched = now.addingTimeInterval(-Double(i % 2) * 60 * 60 * 24)
            }
            manga.lastUpdate = now
        }
        UserDefaults.standard.set(now.timeIntervalSince1970, forKey: Prefs.lastLibraryUpdate)
        try? context.save()
        print("[demo] libreria pronta: \(added.count) manga")
    }
}
#endif
