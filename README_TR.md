# EZAN VAKTİM PRO — R1 kaynak proje

Android Studio'da klasörü açın. Gradle eşitlemesi bittikten sonra **Build > Build APK(s)** seçin.
APK yolu: `app/build/outputs/apk/debug/app-debug.apk`.

## R1 kapsamı
- Kotlin, Android 8.0+; koyu tema; günlük namaz vakitleri.
- AlAdhan API `method=13` ile astronomik vakit hesabı. **Bu Diyanet'in resmî vakit servisi değildir.** Resmî vakitler ile fark olabilir.
- Konum izni ile son bilinen konum; izin yoksa Kahramanmaraş örnek konumu.
- Manyetik pusula + kıble açısı. Gerçek kuzey ile manyetik kuzey farkı henüz düzeltilmediği için pusula yönü yaklaşık olabilir.
- Tam/kısa ezan için kullanıcının kendi yasal ses dosyalarını seçmesi gerekir; paket ses kaydı içermez.
- Seçilen ses, alarm tetiklendiğinde ön plan servisiyle çalınır; ses seçilmemişse bildirim gösterilir.

## Bilinen R1 sınırlamaları (üretime hazır değil)
- APK bu ortamda derlenemedi; kaynak projenin cihaz üzerinde doğrulaması gerekir.
- Yeniden başlatma sonrası alarmlar otomatik tekrar planlanmıyor; uygulama açılıp vakitler yenilenmeli.
- Yalnızca bugün için alarm planlanır; günlük otomatik yenileme, widget, Ramazan ekranı ve hassas geri sayım sonraki sürüme bırakıldı.
- Android 12+ tam alarm izni verilmemişse yaklaşık alarm kullanılır ve gecikme olabilir.
- Ağ kesilirse sadece o gün ve o konum için önceden alınmış vakitler kullanılabilir.
- Android 15 bildirim ve arka plan davranışları gerçek cihazda test edilmelidir.
- Kıble ekranı manyetik kuzeye göre hesaplanan pusula değerini kullanır; gerçek kuzey sapması düzeltmesi ve sensör kalibrasyonu gereklidir.

## Geliştirme hedefleri
R2: günlük güvenilir alarm planlama, cihaz açılışı sonrası yeniden planlama, Diyanet kaynak doğrulaması, pusula manyetik sapma düzeltmesi, il/ilçe seçimi, geri sayım ve widget.
