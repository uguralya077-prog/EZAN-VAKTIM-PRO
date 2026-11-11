# Offline Arabic Quran — attribution and licensing

The APK build generates `app/src/main/assets/quran_arabic.json` from the pinned revision `37b4aa3351c71bd7bfb14dc2dd577656bcafb41b` of [Quran Core Dataset](https://github.com/mjmirza/quran-dataset), by **Mirza Iqbal**, licensed **Creative Commons Attribution 4.0 International (CC BY 4.0)**.

The underlying Arabic Uthmani digital text and structural metadata are attributed to the **Tanzil Project**, licensed **Creative Commons Attribution 3.0 (CC BY 3.0)**. Source: https://tanzil.net . Original source attribution: https://github.com/mjmirza/quran-dataset/blob/main/NOTICE.md .

This app converts the source structure to a smaller JSON containing only the surah name and Arabic verse text; it does not intentionally modify the Arabic verse strings. The build script validates all 114 surah boundaries and 6,236 verse counts. The bundled dataset has no Turkish translation; users may import a separate 114-surah JSON file containing licensed translations. Attribution must be retained when redistributing this APK.

The app's prayer times are fetched from AlAdhan with a Diyanet-style calculation method, and should not be represented as the official published Diyanet timetable.
