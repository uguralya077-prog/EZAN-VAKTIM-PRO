# EZAN VAKTİM PRO R2 — Android kaynak proje

R2: Gün değişiminde vakitleri tekrar alıp alarmları planlayan alıcı, telefon yeniden başladığında/saat veya saat dilimi değiştiğinde vakitleri yenileme, geçmiş alarm iptali.

## Derleme
Android Studio ile `EzanVaktimPro` klasörünü açın. Gradle senkronizasyonundan sonra Build > Build APK(s). APK yolu: app/build/outputs/apk/debug/app-debug.apk.

## Önemli sınırlar
- Bu ZIP **APK değildir**; bu ortamda Android SDK/Gradle derlemesi yapılmadığı için derleme doğrulanmamıştır.
- Vakitler AlAdhan method=13 hesabından gelir, resmî Diyanet yayını olduğu iddia edilmez.
- Ezan ses dosyalarını kullanıcı seçer; pakette telifli ezan ses kaydı yoktur.
- Android 12+ kesin alarm izni verilmezse zamanlama yaklaşık olabilir. Android 13+ bildirim izni gerekir.
- Günlük arka plan yenilemesi internet ve Android'in arka plan çalışma kısıtlarına bağlıdır. Yenileme başarısız olursa bir sonraki güne tekrar denenir; o gün için alarm garantisi yoktur.
- Pusula manyetik kuzeyi temel alır; manyetik sapma düzeltmesi ve kapsamlı cihaz testleri henüz yapılmadı.
- R2 üretim sürümü değildir; gerçek cihaz testi ve izin akışının geliştirilmesi gerekir.
