# Do'konlarga chiqarish (Google Play, Galaxy Store)

## 0. Bir martalik tayyorgarlik
1. **Imzo kaliti** — GitHub → repo → Settings → Secrets and variables → Actions → New repository secret:
   - `NEX_KEYSTORE_PASSWORD` — paroli (faylda berilgan)
   - `NEX_KEYSTORE_B64` — kalitning base64 matni (faylda berilgan)
   Kalit faylini (nexgramm.jks + parol) xavfsiz joyda saqlang: yo'qolsa, Galaxy / to'g'ridan-to'g'ri APK'ni
   yangilab bo'lmaydi (Play'da "upload key"ni tiklash mumkin).
2. **AdMob** — Variables: `ADMOB_APP_ID` (ca-app-pub-…~…) va `ADMOB_BANNER_ID` (ca-app-pub-…/…). Bo'lmasa reklama chiqmaydi.
3. **Maxfiylik siyosati havolasi**: https://github.com/abduraxmonovbobir191-creator/NexGramm/blob/master/PRIVACY.md

## 1. Build
GitHub → Actions → **Build release (stores)** → Run workflow → `store`:
- `play` → `nexgramm-play-<N>.aab` (Google Play'ga shu yuklanadi)
- `galaxy` → `nexgramm-galaxy-<N>.apk` (Galaxy Store)
- `direct` → `nexgramm-direct-<N>.apk` (o'zingiz tarqatadigan, bot orqali karta to'lovi bilan)

Play va Galaxy versiyalarida karta orqali Premium sotilmaydi (do'kon qoidasi: raqamli xizmat faqat do'konning
o'z to'lovi orqali). Keyin Google Play Billing qo'shiladi.

## 2. Google Play Console (play.google.com/console, bir martalik $25)
1. Create app → nomi **NexGramm**, Free, App.
2. Setup → **App signing**: Google'ning kaliti (Play App Signing), bizniki "upload key".
3. Release → Testing → **Internal testing** → `.aab`ni yuklang → o'zingizni tester qiling → sinab ko'ring.
4. Majburiy bo'limlar (App content):
   - Privacy policy: yuqoridagi havola
   - Ads: **Yes, contains ads**
   - Data safety: quyidagi jadval
   - Content rating: so'rovnoma (messenger, foydalanuvchilar o'zaro yozishadi, ijtimoiy lenta)
   - Target audience: 13+ (bolalar uchun emas)
   - **Foreground service** turlari (camera, microphone, mediaPlayback, mediaProjection, location, dataSync): Telegram
     qo'ng'iroqlari, ovozli xabar, musiqa, ekran ulashish, jonli joylashuv, fayl yuklash uchun — har biriga qisqa video
     yoki izoh so'rashadi
   - **REQUEST_INSTALL_PACKAGES**: chatda kelgan APK'ni foydalanuvchi so'raganda, xavfsizlik tekshiruvidan keyin o'rnatish
5. Yangi shaxsiy akkaunt bo'lsa: Production'dan oldin **12 tester 14 kun** yopiq testda bo'lishi shart (closed testing).
6. Production → Create release → `.aab` → Review.

### Data safety (javoblar)
| Ma'lumot | Yig'iladi | Ulashiladi | Maqsad |
|---|---|---|---|
| Ism, username, Telegram ID | Ha | Yo'q | Akkaunt (Lenta, Premium, NEX AI) |
| Rasm/video (Lentaga qo'yilgan) | Ha | Yo'q | Ilova funksiyasi |
| Xabarlar (NEX AI'ga yuborilgan matn) | Ha, ixtiyoriy | Yo'q | Ilova funksiyasi |
| Qurilma/reklama ID | Ha (AdMob) | Ha (Google) | Reklama |
| Ma'lumot shifrlangan holda uzatiladi | Ha (HTTPS / MTProto) | | |
| Foydalanuvchi o'chirishni so'rashi mumkin | Ha (@NexGrammBot) | | |

## 3. Samsung Galaxy Store (seller.samsungapps.com)
1. Seller account (bepul) → Add new app → Android.
2. `nexgramm-galaxy-<N>.apk`ni yuklang, matn va rasmlar (quyida), Privacy policy havolasi.
3. Samsung ham raqamli xaridlar uchun o'z to'lovini (Samsung IAP) talab qiladi — shuning uchun Galaxy build'da ham karta to'lovi yo'q.

## 4. App Store (iPhone)
Bu ilova Android (Java) kodi — App Store'ga qo'yib bo'lmaydi. iPhone uchun Telegram iOS (Swift) asosida alohida
ilova va Apple Developer akkaunti ($99/yil) kerak; bu alohida katta loyiha.

## 5. Do'kon sahifasi matni
**Nomi:** NexGramm

**Qisqa tavsif (80 belgi)**
- uz: Telegram uchun tez va chiroyli mijoz: Lenta, NEX AI, chat qulfi va APK himoyasi
- ru: Быстрый и красивый клиент Telegram: Лента, NEX AI, замок чатов и защита от APK-вирусов
- en: A fast, beautiful Telegram client: Feed, NEX AI, chat lock and APK virus protection

**To'liq tavsif (uz)**
NexGramm — Telegram'ning norasmiy, tez va zamonaviy mijozi. Barcha chatlaringiz, kanallaringiz va guruhlaringiz shu yerda.
• Lenta — rasmlar, videolar va istoriyalar uchun o'z lentangiz, repost va izohlar bilan
• NEX AI — chatni qisqacha xulosa qilish, javob yozish, matnni chiroyli qilish (siz bosgandagina)
• APK himoyasi — chatda kelgan xavfli APK'lar (SMS va karta o'g'rilari) avtomatik bloklanadi
• Chat qulfi — barmoq izi yoki yuz bilan
• O'chirilgan va tahrirlangan xabarlarni saqlash
• Rang va mavzu sozlamalari, silliq animatsiyalar
• 5 tagacha akkaunt (Premium bilan 10 ta)
NexGramm Telegram tomonidan yaratilmagan va u bilan bog'liq emas; Telegram API'dan foydalanadi.

**Полное описание (ru)**
NexGramm — неофициальный, быстрый и современный клиент Telegram.
• Лента — фото, видео и истории, репосты и комментарии
• NEX AI — краткий итог чата, ответы, улучшение текста (только по нажатию)
• Защита от APK — опасные APK из чатов (воры SMS и карт) блокируются автоматически
• Замок чатов — по отпечатку или лицу
• Сохранение удалённых и изменённых сообщений
• Цвета и темы, плавные анимации
• До 5 аккаунтов (с Premium — 10)
NexGramm не создан Telegram и не связан с ним; использует Telegram API.

**Full description (en)**
NexGramm is an unofficial, fast and modern Telegram client.
• Feed — photos, videos and stories with reposts and comments
• NEX AI — chat summaries, reply ideas, text polishing (only when you tap)
• APK protection — dangerous APKs in chats (SMS and card stealers) are blocked automatically
• Chat lock — with fingerprint or face
• Keep deleted and edited messages
• Colors and themes, smooth animations
• Up to 5 accounts (10 with Premium)
NexGramm is not made by or affiliated with Telegram; it uses the Telegram API.

**Rasmlar:** ikonka 512×512 PNG, feature graphic 1024×500, telefon skrinshotlari 2–8 ta (1080×1920 yoki shunga yaqin).
