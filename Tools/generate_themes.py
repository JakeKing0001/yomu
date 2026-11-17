"""Genera Yomu/Resources/themes.json dal sorgente di keiyoushi/extensions-source.

Per ogni estensione registra il "tema" (motore condiviso) e le opzioni principali
che la sottoclasse Kotlin sovrascrive, così l'app sa quale motore Swift usare.

Uso:
  git clone --depth 1 --filter=blob:none --sparse https://github.com/keiyoushi/extensions-source.git ext-src
  cd ext-src && git sparse-checkout set --no-cone 'src/*/*/build.gradle.kts' 'src/*/*/src/' && cd ..
  python Tools/generate_themes.py ext-src Yomu/Resources/themes.json
"""
import glob
import json
import os
import re
import sys

SUPPORTED = {"madara", "mangathemesia", "mangaworld", "pizzareader"}


def read_kotlin(folder):
    text = ""
    for path in glob.glob(os.path.join(folder, "src", "**", "*.kt"), recursive=True):
        with open(path, encoding="utf-8") as f:
            text += f.read() + "\n"
    return text


def main(src_root, out_path):
    result = {}
    for gradle in glob.glob(os.path.join(src_root, "src", "*", "*", "build.gradle*")):
        folder = os.path.dirname(gradle)
        lang = os.path.basename(os.path.dirname(folder))
        name = os.path.basename(folder)
        with open(gradle, encoding="utf-8") as f:
            build = f.read()
        m = re.search(r'theme\s*=\s*"([^"]+)"', build) or re.search(r"themePkg\s*=\s*'([^']+)'", build)
        if not m or m.group(1) not in SUPPORTED:
            continue
        theme = m.group(1)
        kt = read_kotlin(folder)
        entry = {"t": theme}
        if theme == "madara":
            cm = re.search(r"chapterMode\s*(?::[^=]+)?=\s*ChapterMode\.(\w+)", kt)
            if cm:
                entry["cm"] = cm.group(1)
            ms = re.search(r'mangaSubString\s*(?::\s*String)?\s*=\s*"([^"]*)"', kt)
            if ms:
                entry["ms"] = ms.group(1)
            if "MadaraNoAjax()" in kt:
                entry["na"] = True
            df = re.search(r'chapterDateFormat[^=]*=\s*DateTimeFormatter\.ofPattern\(\s*"([^"]+)"', kt)
            if df:
                entry["df"] = df.group(1)
        elif theme == "mangathemesia":
            d = re.search(r'mangaUrlDirectory\s*(?::\s*String)?\s*=\s*"([^"]+)"', kt)
            if d:
                entry["dir"] = d.group(1)
            dp = re.search(r'datePattern\s*(?::\s*String)?\s*=\s*"([^"]+)"', kt)
            if dp:
                entry["df"] = dp.group(1)
        elif theme == "pizzareader":
            a = re.search(r'apiPath\s*(?::\s*String)?\s*=\s*"([^"]+)"', kt)
            if a:
                entry["api"] = a.group(1)
        result[f"{lang}.{name}"] = entry

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(dict(sorted(result.items())), f, separators=(",", ":"))
    counts = {}
    for v in result.values():
        counts[v["t"]] = counts.get(v["t"], 0) + 1
    print(len(result), "estensioni supportate", counts)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
