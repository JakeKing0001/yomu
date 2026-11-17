# Yomu (読む)

Lettore di manga per iPhone e iPad, ispirato a Mihon.

- **Libreria** con categorie, filtri, ordinamento, griglia/lista, badge dei non letti
- **Novità**: aggiornamento della libreria e nuovi capitoli raggruppati per giorno
- **Cronologia** di lettura, con ricerca ed eliminazione
- **Sfoglia**: Fonti (ultimo usato, fissate, per lingua), Estensioni, Migra, ricerca globale
- **Lettore**: paginato destra→sinistra / sinistra→destra, verticale, webtoon, zoom, zone di tocco
- **Download** dei capitoli per la lettura offline
- **Impostazioni**: Aspetto, Libreria, Lettore, Download, Tracciamento, Sfoglia, Dati e archiviazione, Sicurezza e privacy, Avanzate

## Estensioni

Yomu legge i repository di estensioni di Mihon (`index.pb` o `index.min.json`), per esempio:

```
https://github.com/keiyoushi/extensions/raw/repo/index.pb
```

Le estensioni Mihon sono APK Android e non possono girare su iOS. Yomu riproduce in Swift
i motori ("temi") condivisi da molte estensioni:

| Motore | Estensioni Keiyoushi |
|---|---|
| Madara | 244 |
| MangaThemesia | 115 |
| PizzaReader | 10 |
| MangaWorld | 2 |

`Yomu/Resources/themes.json` indica il motore di ogni estensione ed è generato da
`Tools/generate_themes.py` a partire da `keiyoushi/extensions-source`.

## Compilazione

Non serve un Mac: GitHub Actions (`.github/workflows/build.yml`) genera il progetto con
XcodeGen, compila e produce `Yomu.ipa` (non firmato) come artifact. Con un tag `v*` crea anche una release.

Installazione da Windows: [Sideloadly](https://sideloadly.io) o [AltStore](https://altstore.io) con il proprio Apple ID.
