# Klavye

Turkish Q keyboard for Android that follows SwiftKey's layout (key positions, widths, number row, long-press characters). It has no internet permission and no telemetry, no dependencies beyond the Android framework, and the release APK is about 410 KB.

## Ne bu?

SwiftKey'in Türkçe Q düzenini (tuş yerleri, genişlikler, sayı satırı, uzun basma harfleri) birebir izleyen, sıfırdan yazılmış hafif bir Android klavye.

Neden yazıldı: SwiftKey'in telemetri servisi telefonda (Redmi) ana thread'i kilitleyip "uygulama yanıt vermiyor" hataları veriyordu ve klavye bazen açılmıyordu. Aynı düzende ama hafif ve internetsiz bir klavye istendi.

## Özellikler

- Türkçe Q düzeni, sayı satırı, Türkçe harfler, uzun basınca ek harf ve semboller
- Sembol sayfaları
- Kelime önerileri: yaklaşık 60.000 kelimelik Türkçe sıklık sözlüğü ve yazdıkça kullanıcıdan öğrenme. Öneri satırı yalnız yazmaya başlayınca görünür.
- Emoji paneli
- Pano geçmişi: emoji tuşuna uzun bas
- Silme tuşuna basılı tutunca kelime kelime silme
- Shift: küçük, büyük, caps lock, küçük (SwiftKey gibi). Mesaj gönderilip alan boşalınca caps lock kendiliğinden kapanır.
- Tuşa basınca kısa süreli önizleme balonu
- Koyu tema

## Bilerek olmayanlar

- Otomatik düzeltme yok
- Kaydırarak yazma (swipe) yok
- Ayar ekranı yok

## Gizlilik

- `INTERNET` izni yok (manifestte tanımlı değil, uygulama ağa çıkamaz).
- Telemetri ve analitik yok.
- Öğrenilen kelimeler ve pano geçmişi yalnız cihazda kalır.
- Şifre alanlarında öneri ve öğrenme kapalıdır.
- Bağımlılık yok: AndroidX bile kullanılmıyor, yalnız Android framework. Release APK yaklaşık 410 KB.

## Gereksinimler

Android 8.0+ (minSdk 26, targetSdk 36). Yalnızca Redmi Note 13 Pro 4G (HyperOS 3 / Android 16) üzerinde denendi; başka cihazlarda sorun çıkarsa issue açılabilir.

## Derleme ve kurulum

JDK 17+ ve Android SDK gerekir.

```
./gradlew assembleRelease
```

Windows'ta `gradlew.bat assembleRelease`. Çıktı: `app/build/outputs/apk/release/app-release.apk`

Not: release derlemesi kolaylık için debug anahtarıyla imzalanır (`app/build.gradle.kts`). Dağıtmak isteyen kendi imza anahtarını ayarlamalıdır.

Testler (JVM birim testleri: shift, kelime işlemleri, öneri motoru):

```
./gradlew testReleaseUnitTest
```

Kurulum:

```
adb install -r app/build/outputs/apk/release/app-release.apk
```

Sonra uygulamayı aç (kurulum ekranı var), Ayarlar'dan klavyeyi etkinleştir ve varsayılan olarak seç.

## Proje yapısı

`app/src/main/java/com/selman/klavye/` altında:

- `KlavyeService.kt`: InputMethodService
- `layout/`: düzen tanımları
- `view/`: klavye çizimi, öneri satırı, önizleme balonu
- `input/`: shift ve kelime yardımcıları
- `suggest/`: öneri motoru
- `emoji/`
- `clipboard/`
- `setup/`

Ayrıca:

- `tools/build_dict.py`: sözlüğü üretir
- `tools/gen_emoji.py`: emoji verisini üretir

## Lisans

Kod MIT lisanslıdır (`LICENSE`).

Sözlük `app/src/main/assets/dict/tr.txt`, Hermit Dave'in [FrequencyWords](https://github.com/hermitdave/FrequencyWords) projesinden (OpenSubtitles 2018 tabanlı) türetilmiştir ve CC BY-SA 4.0 lisanslıdır. Ayrıntı: `app/src/main/assets/dict/KAYNAK.txt`.

## Not

Bu proje Microsoft veya SwiftKey ile bağlantılı değildir; SwiftKey adı yalnızca düzeni tanımlamak için geçer. SwiftKey'e ait kod, görsel ya da dosya içermez.
