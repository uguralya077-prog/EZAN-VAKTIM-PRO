#!/usr/bin/env python3
"""Read-only source checks for Quran import integrity and APK project wiring."""
from pathlib import Path
import re
import sys

base = Path(__file__).resolve().parents[1]
main = (base / "app/src/main/java/com/ezanvaktim/pro/MainActivity.kt").read_text(encoding="utf-8")
workflow = (base / ".github/workflows/main.yml").read_text(encoding="utf-8")
manifest = (base / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
checks = {
    "114 surah validation": 'surahs.length()!=114' in main,
    "per-surah ayah count": 'canonicalAyahCounts[i]' in main,
    "6,236 ayah count": False,
    "atomic import writes": 'android.util.AtomicFile' in main and 'file.failWrite(output)' in main,
    "Arabic character validation": "verse.any" in main,
    "translation alignment": 'translations.length()!=verses.length()' in main,
    "offline reader": '"İçe Aktarılan Sûre" -> {' in main,
    "bookmark support": 'quran_bookmark_' in main,
    "source attribution": 'translation_source' in main,
    "APK build": ':app:assembleDebug' in workflow,
    "app manifest": '<application' in manifest,
}
match = re.search(r"val canonicalAyahCounts=intArrayOf\((.*?)\)", main, re.S)
if match:
    counts = [int(x.strip()) for x in match.group(1).split(",") if x.strip()]
    checks["6,236 ayah count"] = len(counts) == 114 and sum(counts) == 6236
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} | {name}")
print(f"RESULT={'PASS' if all(checks.values()) else 'FAIL'}")
sys.exit(0 if all(checks.values()) else 1)
