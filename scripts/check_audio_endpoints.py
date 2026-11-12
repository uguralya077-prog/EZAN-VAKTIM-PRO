#!/usr/bin/env python3
"""CI smoke checks: actual remote Quran audio availability, not playback on device."""
import urllib.request
import urllib.error

URLS = {
    "surah_alafasy": "https://cdn.islamic.network/quran/audio-surah/128/ar.alafasy/1.mp3",
    "ayah_alafasy": "https://cdn.islamic.network/quran/audio/128/ar.alafasy/1.mp3",
    "ayah_husary": "https://cdn.islamic.network/quran/audio/128/ar.husary/1.mp3",
    "ayah_abdulbasit": "https://cdn.islamic.network/quran/audio/128/ar.abdulbasitmurattal/1.mp3",
}
for name, url in URLS.items():
    req = urllib.request.Request(url, headers={"Range": "bytes=0-1023", "User-Agent": "EzanVaktimPro-CI/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = resp.read(1024)
            ctype = resp.headers.get("Content-Type", "")
            if resp.status not in (200, 206) or not data or data[:1] == b"<" or "html" in ctype.lower():
                raise RuntimeError(f"not playable media: status={resp.status}, content-type={ctype}")
            print(f"PASS {name}: HTTP {resp.status}, {ctype}, first {len(data)} bytes")
    except Exception as e:
        raise SystemExit(f"FAIL {name}: {e}")
