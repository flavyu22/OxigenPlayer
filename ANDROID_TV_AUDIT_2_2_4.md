# OxigenPlayer Pro 2.2.4 — audit și reparații Android TV

Data: 2026-09-19. Bază: arhiva 2.2.3 furnizată. Versiune surse: 2.2.4, versionCode 15.

**Stare: reparații implementate în surse și compilate pe Windows. Toate cele opt variante `Debug` au trecut testele unitare, lint-ul (cu `abortOnError`) și asamblarea; APK-urile de debug au fost generate local. Validarea pe un Android TV fizic, testele instrumentate pe dispozitiv/emulator și artefactele `Release` semnate rămân neexecutate. Nu este o certificare de funcționare perfectă.**

## Probleme corectate

| Prioritate | Constatare în surse | Modificare |
|---|---|---|
| Ridicată | Linkurile personalizate fără MIME nu corespundeau filtrului manifestului | Filtru separat pentru `oxigenplayer://`; filtrul cu MIME rămâne compatibil |
| Ridicată | Back era tratat atât în evenimentele de tastatură, cât și în dispatcher; ieșirea cerea trei apăsări | Un singur traseu: închidere dialog, ascundere controale, apoi ieșire TV; salvare poziție și oprire explicită player |
| Ridicată | Cache-ul MediaStore putea fi returnat înainte de verificarea accesului și după schimbarea permisiunilor | Cache asociat permisiunilor și generației, expirare 15 secunde, fără reutilizare la acces parțial; refresh la revenirea în browser |
| Medie | Cererea de focus era făcută în afara compoziției itemului din lista lazy | Focus inițial cerut o singură dată, din itemul atașat, în browser și dialogul de piste |
| Medie | Actualizarea duratei putea muta repetat focusul la controlul implicit | Efectul urmărește disponibilitatea duratei, nu fiecare schimbare numerică |
| Medie | Apăsarea lungă a comenzilor globale putea comuta repetat redarea sau meniurile | Repetițiile comenzilor de tip comutare sunt consumate; derularea repetată rămâne disponibilă |
| Medie | Cronometrul ascundea controalele în timpul navigării sau al unui dialog | Resetare la interacțiunea cu telecomanda și suspendare cât timp un dialog este activ |
| Medie | Dialogul de piste citea valori fără abonare directă la schimbările playerului | Listener cu stare Compose pentru piste și parametri; dezabonare la închidere |
| Medie | Oprirea tickerului în dialoguri oprea și repetarea A-B | Tickerul rămâne activ când bucla A-B rulează și activitatea este vizibilă |
| Medie | Bufferul video nu avea o țintă explicită de memorie adaptată dispozitivului | 24/40/64 MiB după profilul RAM; țintă pentru allocator, nu limită a întregului proces |
| Medie | Cache-ul listei de fișiere rămânea în memorie la presiune de memorie | Invalidare în rutina existentă de eliberare a cache-urilor |
| Medie | Cursorul MediaStore nul era interpretat ca scanare reușită fără rezultate | Eroarea este propagată spre tratarea existentă a eșecurilor de scanare |
| Scăzută | Starea vizuală de focus/scalare putea persista la detașare sau dezactivarea animațiilor | Resetare focus, progres și scalare |

Protecția suplimentară a derulării globale verifică absența unui dialog și posibilitatea de seek. Nu au fost eliminate funcții sau furnizori de traducere fără dovadă de defect.

## Domenii inspectate și limite

Au fost inspectate manifestul, configurarea variantelor și dependențelor, activitatea principală, serviciul de redare, configurarea ExoPlayer, gestionarea focusului, browserul local, selectarea pistelor, ciclul de viață, persistența poziției și fluxurile de traducere/alegere a fișierelor. Aceasta este o revizie statică orientată spre Android TV, nu o verificare exhaustivă a tuturor ramurilor și serviciilor externe.

În sursele de traducere sunt deja prezente descărcarea modelelor ML Kit fără condiție obligatorie Wi-Fi, limite pentru răspunsuri și propagarea anulării. Funcționarea ML Kit pe modelul concret de televizor și disponibilitatea furnizorilor online nu au fost testate. Gemini Nano nu trebuie presupus disponibil pe TV; varianta Classic evită această dependență.

Metadatele `update.json` sunt aliniate la versiunea curentă (`versionCode` 15, `versionName` 2.2.4) și rămân dezactivate, fără URL public sau checksum inventat pentru un APK `Release` inexistent. Rapoartele vechi din proiect sunt istorice; acest raport descrie starea livrării curente.

## Verificări executate

- `python3 scripts/verify-project-policy.py`: PASS, inclusiv checksum-ul wrapperului, politica proiectului, localizările și scanarea secretelor. `local.properties` a fost eliminat din arhivă (era semnalat de scanarea de secrete); SDK-ul se rezolvă prin variabila `ANDROID_HOME`.
- `python3 -m unittest discover -s scripts/tests -v`: 3 teste Python de împachetare, PASS. Acestea nu testează playerul Android.
- `py_compile` pentru toate scripturile din `scripts/`, inclusiv `scripts/tests/`: PASS.
- Build Gradle complet pe Windows (JDK 25, SDK 37, Gradle 9.7.1): `test<Variant>DebugUnitTest`, `lint<Variant>Debug` (cu `abortOnError = true`) și `assemble<Variant>Debug` pentru toate cele opt variante — **BUILD SUCCESSFUL**; 20 de clase de teste unitare au trecut fără eșecuri.
- APK-urile de test instrumentat compilează: `:app:assembleDirectPlatformClassicDebugAndroidTest` și `:app:assembleDirectFullNanoDebugAndroidTest` — **BUILD SUCCESSFUL**.
- 27 fișiere XML: parsare reușită.
- Metadatele APK-urilor de debug, citite cu `aapt2 dump badging`, confirmă `versionCode=15`, `versionName=2.2.4`, `targetSdkVersion=37`.
- Verificare lexicală simplă a delimitatorilor în fișierele Kotlin modificate: fără dezechilibre; nu înlocuiește compilatorul.
- Revizuire a diferențelor față de arhiva originală. Patch și jurnale incluse în `audit-evidence/`.
- Integritatea ZIP și concordanța fișierelor arhivate cu sursele locale: verificate la împachetare.

## Verificări încă neexecutate

- Testele instrumentate (`:app:connected<Variant>DebugAndroidTest`) nu au fost rulate: mediul de lucru nu are dispozitiv sau emulator Android TV conectat prin ADB.
- Artefactele `Release` nu au fost construite: lipsește keystore-ul de lansare (`OXIGEN_RELEASE_*`) și nu s-a publicat niciun APK semnat.
- Testarea pe hardware TV real (1–2 GB RAM, telecomandă, codecuri hardware/software, HDR/passthrough) rămâne de făcut conform matricei de acceptare de mai jos.
- Compatibilitatea binară Media3 1.11.1 cu extensia NextLib 1.10.1-0.13.0 este confirmată la link/compilare pentru variantele `Full`, dar redarea efectivă nu a fost verificată pe dispozitiv.

Au fost adăugate două teste instrumentate care compilează (`assemble...AndroidTest` PASS), dar **nu au fost rulate pe dispozitiv**: navigare D-pad urmată de activarea controlului selectat și rezolvarea linkului personalizat fără MIME prin manifestul instalat.

## Cum se compilează pe Windows

1. Deschide proiectul în Android Studio; instalează platforma SDK 37 și JDK 25, conforme configurației existente. Este necesar acces la depozitele Gradle/Maven.
2. `local.properties` nu este inclus în arhivă, deoarece scanarea de secrete îl respinge. În mediul local, fie setează variabila `ANDROID_HOME` către SDK, fie creează local fișierul `local.properties` cu linia `sdk.dir=<cale SDK>`; fișierul este ignorat și exclus la împachetare. Build-ul de validare a fost executat cu `ANDROID_HOME` setat și fără `local.properties`.
3. Rulează `BUILD_ANDROID_TV.bat`. Scriptul execută teste unitare, lint și compilarea variantei `DirectPlatformClassicDebug`, oprindu-se la eroare.
4. APK-ul, dacă toate sarcinile reușesc, este în `app/build/outputs/apk/directPlatformClassic/debug/`. Instalează-l cu `adb install -r <cale-apk>`. Actualizarea peste o instalare existentă necesită aceeași cheie de semnare; nu dezinstala automat aplicația și datele ei.
5. Cu un Android TV conectat prin ADB, rulează `gradlew.bat :app:connectedDirectPlatformClassicDebugAndroidTest`.
6. Pentru codecurile software rulează separat testele, lint și asamblarea `DirectFullClassicDebug`. Variantele Release necesită semnarea configurată; nu sunt pregătite de distribuire doar prin acest audit.

## Matrice de acceptare pe TV — încă de executat

- Pornire din launcher TV, inclusiv fără permisiuni și fără acces la internet; verificare focus vizibil.
- Numai telecomandă: toate meniurile, liste lungi, schimbarea surselor, apăsări rapide/lungi, OK și Back.
- Back: închide dialogul, ascunde controalele, apoi părăsește aplicația fără audio rămas în fundal.
- Fișier MP4/H.264/AAC, MKV/HEVC, 1080p și 4K; piste audio multiple, AC3/EAC3/DTS pe varianta potrivită. HDR/Dolby Vision/passthrough se verifică pe hardware compatibil.
- USB: conectare/deconectare, reîmprospătare, refuz/revocare permisiuni și revenire din setările Android.
- HTTPS, HLS, DASH, RTSP și HTTP local în varianta Direct; întrerupere/revenire rețea, seek și reluare poziție.
- Subtitrări interne, SRT/VTT/ASS externe, sincronizare, selectare pistă fără text dublat; traducere după descărcarea modelelor.
- Home/revenire, standby/revenire, PiP unde există suport, repetare A-B cu meniul deschis.
- Sesiune de minimum 60 minute pe TV cu 1–2 GB RAM; monitorizare blocări, cadre pierdute, memorie și rebuffering. Țintele mai mici de buffer pot reduce toleranța la fluctuații pentru bitrate foarte mare.

Unele televizoare nu oferă un selector de documente funcțional. Importul/exportul subtitrărilor prin acel selector și vizibilitatea fișierelor USB neindexate de MediaStore rămân limitări ale traseului existent; această livrare nu introduce un explorator SAF complet. Accesul HTTP public rămâne blocat de politica existentă, fără relaxări făcute doar pentru compatibilitate.

## Referințe consultate

- [Android Developers — TV navigation](https://developer.android.com/training/tv/get-started/navigation): navigare D-pad, focus vizibil și ieșire previzibilă fără confirmări repetate.
- [Android Developers — AGP 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes): API maxim 37, Gradle minim 9.6.0, JDK minim 17, Kotlin 2.4.20 (linia built-in AGP 9.4, conform gradle/libs.versions.toml). Cerința JDK 25 provine din acest proiect.
- [NextLib — documentație](https://github.com/anilbeesetti/nextlib): prioritatea rendererelor și limitele recuperării automate în cazul erorilor de decodare.
