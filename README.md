# Papertrail — Android tablet not defteri

GoodNotes'tan ilham alan, Kotlin + Jetpack Compose ile hazırlanmış tablet öncelikli not alma uygulaması başlangıç projesi. Sıcak defter kapakları, sade bir not rafı ve dikkat dağıtmayan yazı alanını bir araya getirir.

## Android Studio'da çalıştırma

1. Android Studio'da **Open** ile bu klasörü açın.
2. Gradle eşitlemesinin tamamlanmasını bekleyin (Android Gradle Plugin 8.7.3, Kotlin 2.0.21; JDK 17 önerilir).
3. Android 8.0+ tablet veya emülatör seçip **Run**'a basın.

Bu çalışma ortamında Java/Android SDK kurulu olmadığından APK derlemesi burada doğrulanamadı. İlk Gradle eşitlemesi internet bağlantısı gerektirir.

## Şu anki özellikler

- Tablet genişliğine uyumlu kitaplık ve renkli, açılabilir defter kapakları
- Yeni defter oluşturma, adla arama, yerel SharedPreferences kaydı
- Basınca duyarlı stylus/touch çizim yüzeyi; kalem, vurgulayıcı ve silgi
- Renk paleti, geri al/yinele, kalıcı çizgi verileri
- Çizgili, kareli ve kenar çizgili sayfa şablonları
- Sayfa ekleme kontrolleri, koyu görünüm, PDF dosyası oluşturma
- Türkçe ve İngilizce Android string kaynakları

## Bilinen prototip sınırları

Bu ilk dikey dilimdir: lasso, metin aracı, PDF'ye çizimlerin aktarılması, çok sayfalı not verisi/thumbnail'ları ve sistem paylaşım menüsü henüz tamamlanmadı. Çizim koordinatları sayfa piksel alanına göre saklanır; ekran boyutları arası ölçekleme ve gerçek sınırsız zoom/pan sıradaki geliştirme adımlarıdır. PDF dışa aktarma şu an şablonlu tek sayfalık bir önizleme oluşturur ve dosyayı uygulamanın önbelleğine yazar.

## Proje yapısı

- `app/src/main/java/com/papertrail/notebook/MainActivity.kt` — Compose arayüzü, çizim ve yerel veri işlemleri
- `app/src/main/res/values*/strings.xml` — Türkçe/İngilizce kaynaklar
