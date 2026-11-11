#!/usr/bin/env python3
"""Prepare the openly attributed Arabic Quran for offline APK packaging."""
import json
from pathlib import Path
from urllib.request import urlopen

REVISION = "37b4aa3351c71bd7bfb14dc2dd577656bcafb41b"
URL = f"https://raw.githubusercontent.com/mjmirza/quran-dataset/{REVISION}/data/quran.json"
COUNTS = [7,286,200,176,120,165,206,75,129,109,123,111,43,52,99,128,111,110,98,135,112,78,118,64,77,227,93,88,69,60,34,30,73,54,45,83,182,88,75,85,54,53,89,59,37,35,38,29,18,45,60,49,62,55,78,96,29,22,24,13,14,11,11,18,12,12,30,52,52,44,28,28,20,56,40,31,50,40,46,42,29,19,36,25,22,17,19,26,30,20,15,21,11,8,8,19,5,8,8,11,11,8,3,9,5,4,7,3,6,3,5,4,5,6]
with urlopen(URL, timeout=90) as response:
    source = json.load(response)
assert len(source["surahs"]) == 114
out = {"source": "Quran Core Dataset — Mirza Iqbal (CC BY 4.0); Arabic: Tanzil Project (CC BY 3.0), https://tanzil.net", "surahs": []}
for index, chapter in enumerate(source["surahs"]):
    assert int(chapter["number"]) == index + 1
    ayahs = chapter["ayahs"]
    assert len(ayahs) == COUNTS[index], (index + 1, len(ayahs))
    verses = []
    for n, ayah in enumerate(ayahs):
        assert int(ayah["number"]) == n + 1
        verse = ayah["text"]
        assert isinstance(verse, str) and verse.strip() and any("\u0600" <= c <= "\u06ff" for c in verse)
        verses.append(verse)
    out["surahs"].append({"name": chapter["name_arabic"], "verses": verses})
assert sum(len(x["verses"]) for x in out["surahs"]) == 6236
target = Path(__file__).resolve().parents[1] / "app/src/main/assets/quran_arabic.json"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
print(f"PASS | Bundled 114 surahs, 6236 ayahs | {target.stat().st_size} bytes")
