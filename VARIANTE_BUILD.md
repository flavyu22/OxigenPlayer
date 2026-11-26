# OxigenPlayer Pro — o singură variantă

Din 14 noiembrie 2026 proiectul nu mai are arome (`play`/`direct`, `platform`/`full`,
`api24`/`genai`). Există o singură aplicație, care include tot:

| Ce include | Detalii |
|---|---|
| Instalare directă din APK | auto-update propriu, verificat prin SHA-256 și semnătură |
| HTTP doar către rețeaua locală | NAS, TorrServer, server local; HTTP public rămâne blocat |
| Decodoare software FFmpeg (NextLib) | rezervă automată când decodorul hardware eșuează (E-AC3, HEVC 10-bit) |
| Traducere subtitrări | ML Kit pe dispozitiv și provideri online |

Cerință: Android 7.0 (API 24) sau mai nou.

Gemini Nano a fost scos complet: are nevoie de Android AICore, care nu există pe
televizoare, și oprea aplicația la pornire.

## Comenzi

| Scop | Comandă | Rezultat |
|---|---|---|
| APK de test (debug) | `BUILD_DEBUG.bat` sau `gradlew.bat :app:assembleDebug` | `app\build\outputs\apk\debug\app-debug.apk` |
| Teste + lint + APK debug | `BUILD_ANDROID_TV.bat` | la fel, plus rapoarte în `app\build\reports\` |
| APK optimizat (release) | `gradlew.bat :app:lintRelease :app:assembleRelease` | `app\build\outputs\apk\release\app-release.apk` |

## Care este varianta „cea mai performantă”

Build-ul **release**. Față de debug:

- cod optimizat și micșorat cu R8 (full mode), fără cod de depanare;
- Baseline Profile inclus, deci pornire mai rapidă;
- doar ABI-urile ARM (`arm64-v8a`, `armeabi-v7a`): aproximativ 57 MB în loc de 196 MB.

Release-ul trebuie semnat cu cheia ta. Fără cele patru proprietăți de mai jos, build-ul se
oprește intenționat:

```
OXIGEN_RELEASE_STORE_FILE
OXIGEN_RELEASE_STORE_PASSWORD
OXIGEN_RELEASE_KEY_ALIAS
OXIGEN_RELEASE_KEY_PASSWORD
```

Se pot da ca variabile de mediu sau în `%USERPROFILE%\.gradle\gradle.properties`. Nu le
pune în proiect. Păstrează aceeași cheie pentru toate versiunile, altfel actualizarea peste
o versiune instalată nu merge.

## Licență

Pentru că aplicația include acum mereu NextLib/FFmpeg (GPLv3), orice APK distribuit trebuie
însoțit de sursa corespunzătoare. Vezi `SOURCE_OFFER_FULL.md` și
`scripts/package-gpl-source.py`.

## Google Play

Această variantă nu este potrivită pentru Google Play: cere `REQUEST_INSTALL_PACKAGES` și
are auto-update propriu. Sursele vechilor arome sunt păstrate în
`audit-evidence/removed-flavors-2026-11-14/` dacă va fi nevoie de o ediție separată.
