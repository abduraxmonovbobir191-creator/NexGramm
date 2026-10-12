# NexGramm
Telegram Android (DrKLO/Telegram) forki. Rasmiy Telegram serverlariga ulanadi. Egasi faqat telefondan ishlaydi, lokal build yo'q.
- Build: faqat GitHub Actions, "Build debug APK" workflow (workflow_dispatch), modul TMessagesProj_AppStandalone, arm64-v8a, -Pandroid.injected.testOnly=false majburiy. Artifact: nexgramm-debug-arm64-v8a.apk (zip'siz, telefonda darrov o'rnatiladi).
- Paket: uz.nexgramm.app. Nom: NexGramm. Ikonka: binafsha fonda qora ✦.
- API ID/HASH: faqat CI secret'larida (TG_API_ID, TG_API_HASH), repoga yozilmaydi.
- NEX AI: NexCode backend (POST /api/v1/chat, SSE, core JWT — NexCode B5.1). Manzil build'da: NEX_API_URL (vars yoki workflow input nex_api_url), https majburiy; bo'sh = "ulanmagan". AI faqat foydalanuvchi bosganda, birinchi marta rozilik so'raladi.
- google-services plagini o'chirilgan, push-xabarlar hozircha ishlamaydi.
- Tema: assets/nexgramm_light.attheme shablon; uz.nexgramm.core.NexPalette uni yorug'/qorong'i rejim va 9 urg'u rangiga aylantiradi (Theme.applyNexgrammColors, barcha built-in temalar). Telegram ko'ki qolmaydi. Tanlov: Sozlamalar → Ko'rinish va rang (NexAppearanceActivity), prefs "nexgramm_theme".
- Ranglar: fon #F4F4F7, karta #FFFFFF, urg'u #1F1F24, ikkilamchi #D7C4FA, ikkilamchi matn #8A8A93.
- Qoidalar: javoblar o'zbekcha va qisqa; har vazifada eng kam o'zgarish; build eng ko'pi 2 urinish; ish tugagach master'ga merge.
