# Kur'an çevrim dışı içe aktarma biçimi

Uygulama, **kullanıcının sağladığı** UTF-8 JSON dosyasını okuyabilir. Bu depoda Kur'an'ın tamamı veya Türkçe meal metni **paketlenmemiştir**. Arapça metnin doğruluğunu ve Türkçe mealin dağıtım iznini içe aktarmadan önce bağımsız olarak kontrol edin.

## Dosya şeması

```json
{
  "surahs": [
    {
      "name": "Kaynakta geçen sûre adı",
      "verses": ["Arapça ayet metni 1", "Arapça ayet metni 2"],
      "translations": ["Türkçe meal 1", "Türkçe meal 2"]
    }
  ]
}
```

Bu örnek **gerçek Kur'an metni değildir**, yalnızca alanları gösterir. `surahs` tam 114 öğe içermelidir; öğeler Mushaf sırasıyla yerleştirilmelidir. Her sûre en az bir Arapça ayet içerir. `translations` isteğe bağlıdır; varsa aynı sûredeki `verses` ile eşit uzunlukta olmalıdır. Dosya 8 MiB'yi geçemez. Kaynak lisansı, meal yazarı, baskı ve ayet numaralandırması ayrıca doğrulanmalıdır. Uygulamanın biçim denetimi, ayetlerin dinî doğruluğunu veya lisans durumunu teyit etmez.

## Son kullanıcı akışı

Kur'an > 114 sûre çevrim dışı metin dosyası içe aktar > JSON seç > İçe aktarılan çevrim dışı Kur'anı oku. Metin cihazın uygulamaya özel depolama alanına kaydedilir. Okuyucuda sûre arama, isteğe bağlı meal, önceki/sonraki sûre, son okunan sûreye dönme ve paylaşma bulunur.

## Kabul testi

1. 113 sûre içeren dosya reddedilmeli.
2. Arapça ayet içermeyen sûre reddedilmeli.
3. Meal dizisi ile ayet sayısı farklıysa reddedilmeli.
4. Geçerli 114 sûreli dosya içe alınmalı, uygulama yeniden açıldığında korunmalı.
5. Çevrim dışı modda arama ve sûre görüntüleme çalışmalı.

**Not:** Son iki madde gerçek Android cihazında henüz doğrulanmamıştır.
