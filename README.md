# Papertrail — Android tablet not defteri

GoodNotes'tan ilham alan, Kotlin + Jetpack Compose Foundation üzerinde Material 3 bileşenleri kullanmadan hazırlanmış tablet öncelikli not alma uygulaması. Özel tipografi, sıcak kapaklar, kendi çizim motoru ve Papertrail'e özgü kontrollerle bir dijital kâğıt stüdyosu oluşturur.

## Android Studio'da çalıştırma

1. Android Studio'da **Open** ile bu klasörü açın.
2. Gradle eşitlemesinin tamamlanmasını bekleyin (Android Gradle Plugin 8.7.3, Kotlin 2.0.21; JDK 17 önerilir).
3. Android 8.0+ tablet veya emülatör seçip **Run**'a basın.

Bu çalışma ortamında Java/Android SDK kurulu olmadığından APK derlemesi burada doğrulanamadı. İlk Gradle eşitlemesi internet bağlantısı gerektirir.

## Uygulama özellikleri

- Tablet kitaplığı: yeni defter, adla arama, favorileme, yeniden adlandırma ve silme
- Çok sayfalı defterler; sayfa başına yerel otomatik kayıt ve sayfa gezgini
- Kalem motoru: basınç duyarlı, EMA ile filtrelenmiş uç; Catmull–Rom merkez çizgisi; tek dolgulu vektör kontur; yuvarlak ve yumuşak uçlar; hız uyarlamalı sabitleme
- Canlı kalem örnekleri ayrı çizim bileşeninde tutulur; kitaplık ve sayfa gezinme panelleri her örnekte yeniden oluşturulmaz
- Basınçlı çizgi, çok sayıda segment/circle çizimi yerine tek bir doldurulmuş vektör konturla çizilir; sayfa verileri arka planda yüklenir ve yazılır
- Kâğıt, tamamlanmış mürekkep ve canlı kalem ayrı GPU katmanlarında tutulur; pinch hareketi yalnızca katman dönüşümünü günceller
- Ayarlardan yalnız-stylus çizim modu; parmak/avuç çizimini devre dışı bırakma
- İki parmakla yakınlaştırma ve kaydırma; alt çubuktan zoom sıfırlama ve yüzde ayarı
- Kalem, yarı saydam vurgulayıcı, silgi, metin ekleme, lasso seçimi
- Lasso ile birden fazla çizgiyi seçme, silme ve kopyalama
- Serbest çizim, düz çizgi, dikdörtgen ve elips araçları
- Renk paleti ve ayarlanabilir kalem ucu kalınlığı
- Geri al/yinele, çizgili/kareli/kenar çizgili/boş kağıt şablonları
- Koyu tema, yakınlaştırma kontrolleri, PDF'yi sistem dosya seçicisine dışa aktarma
- Yerel not verisi Android SharedPreferences'ta saklanır

## Bilinen sınırlar

Bu sürüm internet bulut yedekleme/senkronizasyonu, PDF içe aktarma/üzerine yazma, gerçek parmak reddi ve doğal el yazısı OCR'sı içermez. Kalem basınç örneklemesini cihazın sağladığı pointer verisiyle sınırlar; farklı tablet/digitizer modellerinde gecikme ve basınç eğrisi değişebilir. Sayfa önizlemeleri çizgileri küçültülmüş şekilde gösterir; metin öğeleri henüz küçük resme dahil edilmez. İngilizce string kaynakları mevcut olsa da yeni özel arayüzün tüm metinleri henüz yerelleştirilmemiştir.

## Proje yapısı

- `app/src/main/java/com/papertrail/notebook/MainActivity.kt` — Compose arayüzü, çizim ve yerel veri işlemleri
- `app/src/main/res/values*/strings.xml` — Türkçe/İngilizce kaynaklar
