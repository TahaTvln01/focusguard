# FocusGuard

Instagram ve YouTube'daki bağımlılık yapan akışları engelleyen, zorunlu mola verdiren küçük bir erişilebilirlik servisi.
İzin yok, internet yok, veri toplama yok. Tespit kuralları tek dosyada: `app/src/main/java/app/focusguard/Rules.java`.

## Kurulum
1. Telefonda repo → **Releases** → `focusguard.apk` indir.
2. Dosyayı aç → "Bilinmeyen uygulamaları yükle" iznini ver → **Yükle**.
3. Android 13+: Ayarlar → Uygulamalar → FocusGuard → ⋮ → **Kısıtlanmış ayarlara izin ver**.
4. FocusGuard'ı aç → **Erişilebilirlik ayarlarını aç** → Yüklü uygulamalar → FocusGuard → Aç.
5. Anahtarları, mola sürelerini ve günlük hedefi istediğin gibi ayarla.

## Özellikler
- Reels / Shorts sekmesini kapatma, açılan videodan sonrakine kaydırmayı engelleme.
- Zorunlu mola: her X kullanımda Y mola; ekranda yuvarlak sayaç (kenara yapışır, alta sürükleyince gizlenir).
- Günlük ekran süresi halkası ve hedef: hedef aşılınca uygulama yalnızca onay cümlesiyle, en fazla 10 dk'lık izinlerle açılır.
- "Bana bi 5 dakika ver": uygulama başına, günlük bütçe içinde o uygulamadaki tüm engelleri geçici kaldırır; istediğin an çıkabilirsin.
- Süreler kaydırmalı tekerleklerle seçilir; her ayarın yanındaki ? ne işe yaradığını anlatır.
- Bir korumayı kapatmak 10 sn bekleme + onay cümlesi ister.
- İsteğe bağlı: gizlenen sayaç bildirim olarak geri sayar (yalnızca bu seçenek açılırsa bildirim izni istenir).

## Kaldırma
Ayarlar → Uygulamalar → FocusGuard → Kaldır.

## Katkı
PR'lar `dev` dalına; bkz. [CONTRIBUTING.md](CONTRIBUTING.md).
