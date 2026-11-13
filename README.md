# FocusGuard

Instagram ve YouTube'daki bağımlılık yapan akışları engelleyen, zorunlu mola verdiren küçük bir erişilebilirlik servisi.
İzin yok, internet yok, veri toplama yok. Tespit kuralları tek dosyada: `app/src/main/java/app/focusguard/Rules.java`.

## Kurulum
1. Telefonda repo → **Releases** → `focusguard.apk` indir.
2. Dosyayı aç → "Bilinmeyen uygulamaları yükle" iznini ver → **Yükle**.
3. Android 13+: Ayarlar → Uygulamalar → FocusGuard → ⋮ → **Kısıtlanmış ayarlara izin ver**.
4. FocusGuard'ı aç → **Erişilebilirlik ayarlarını aç** → Yüklü uygulamalar → FocusGuard → Aç.
5. Anahtarları ve mola sürelerini istediğin gibi ayarla.

## Kaldırma
Ayarlar → Uygulamalar → FocusGuard → Kaldır.

## Katkı
PR'lar `dev` dalına; bkz. [CONTRIBUTING.md](CONTRIBUTING.md).

## Sürüm çıkarma (repo sahibi)
- Bir kez: Settings → Secrets and variables → Actions → `KEYSTORE_B64` (base64 keystore, alias `focusguard`) ve `KEYSTORE_PASS`.
- `app/build.gradle` içinde `versionName`/`versionCode` artır, `main`e birleştir → Actions imzalı APK'yı derleyip Release oluşturur.
