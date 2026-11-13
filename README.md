# FocusGuard

Instagram ve YouTube'daki bağımlılık yapan akışları engelleyen küçük bir erişilebilirlik servisi.
İzin yok, internet yok, veri toplama yok. Tespit kuralları tek dosyada: `app/src/main/java/app/focusguard/Rules.java`.

## Kurulum
1. Telefonda repo → **Releases** → `focusguard.apk` indir.
2. Dosyayı aç → "Bilinmeyen uygulamaları yükle" iznini ver → **Yükle**.
3. Android 13+: Ayarlar → Uygulamalar → FocusGuard → ⋮ → **Kısıtlanmış ayarlara izin ver**.
4. FocusGuard'ı aç → **Erişilebilirlik ayarlarını aç** → Yüklü uygulamalar → FocusGuard → Aç.
5. Anahtarları istediğin gibi ayarla.

## Kaldırma
Ayarlar → Uygulamalar → FocusGuard → Kaldır.

## Sürüm çıkarma
`app/build.gradle` içinde `versionName`/`versionCode` artır, `main`e push et → Actions APK'yı derleyip yeni Release oluşturur.
