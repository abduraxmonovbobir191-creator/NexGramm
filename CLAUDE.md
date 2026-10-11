# NexGramm
Telegram Android (DrKLO/Telegram) forki. Rasmiy Telegram serverlariga ulanadi. Egasi faqat telefondan ishlaydi, lokal build yo'q.
- Build: faqat GitHub Actions, "Build debug APK" workflow (workflow_dispatch), modul TMessagesProj_AppStandalone, arm64-v8a, -Pandroid.injected.testOnly=false majburiy.
- Paket: uz.nexgramm.app. Nom: NexGramm. Ikonka: binafsha fonda qora ✦.
- API ID/HASH: faqat CI secret'larida (TG_API_ID, TG_API_HASH), repoga yozilmaydi.
- google-services plagini o'chirilgan, push-xabarlar hozircha ishlamaydi.
- Tema: assets/nexgramm_light.attheme, Theme.java uni barcha light temalarga qo'llaydi. Qorong'i temalar hali Telegram'niki.
- Ranglar: fon #F4F4F7, karta #FFFFFF, urg'u #1F1F24, ikkilamchi #D7C4FA, ikkilamchi matn #8A8A93.
- Qoidalar: javoblar o'zbekcha va qisqa; har vazifada eng kam o'zgarish; build eng ko'pi 2 urinish; ish tugagach master'ga merge.
