# OxigenPlayer Pro 2.1 — raport final de remediere

**Data:** 6 august 2026  
**Versiune:** `versionCode 8`, `versionName 2.1`  
**Bază:** auditul static `AUDIT_OxigenPlayer_Pro_2.0.md`  
**Verdict sursă:** **REMEDIERE IMPLEMENTATĂ / RELEASE CANDIDATE NECOMPILAT LOCAL**

## Rezumat

Au fost tratate individual **26 de constatări**: 2 ridicate, 9 medii și 15 scăzute. Două au o poartă externă transparentă:

1. Varianta Direct trebuie să permită cleartext la poarta Android pentru hosturi LAN arbitrare; deoarece Network Security Config definește excepții prin domenii, nu CIDR/RFC1918, restricția efectivă este aplicată în politica URI și în două niveluri OkHttp, inclusiv verificarea adresei rutei.
2. `gradle/verification-metadata.xml` complet poate fi generat numai într-un mediu conectat după rezolvarea grafului. Proiectul activează dependency locking și include un job CI care produce baseline-ul pentru revizuire și commit înainte de release.

## Inventar după remediere

- 157 fișiere sursă/documentație;
- 71 fișiere Kotlin;
- 14,661 linii Kotlin;
- 74 metode `@Test`;
- 8 limbi Android native, câte 196 resurse incluzând `app_name`;
- 8 combinații de produs și matrice debug/release completă;
- teste instrumentate API 37 pentru telefon și Android TV.

## Schimbări majore

- licențiere GPL-3.0-only completată, sursă NextLib exactă obligatorie pentru `full`;
- `rtsps://` reparat în calea reală `Intent.ACTION_VIEW`;
- eliminare `androidx.security:security-crypto`; AES-GCM direct cu Android Keystore;
- manager unic de preferințe și inițializare criptografică pe IO;
- HTTP Direct limitat la LAN, verificat și după DNS/route, inclusiv redirecturi;
- localizare migrată la resurse Android și integrare per-app language Android 13+;
- CI API 37, CodeQL, dependency review, secret scan, dependency baseline;
- semnătură inițială APK, parsing numeric, lifecycle serviciu și configurații TV întărite.

## Statusul constatărilor

| ID | Severitate | Status | Remediere |
|---|---|---|---|
| H-1 | Ridicată | REMEDIAT | LICENSE GPL-3.0-only la rădăcină; SPDX pe Kotlin; notice în flavor full; NextLib fixat la tag v1.10.1-0.13.0 / commit 773c841f86d9775abd0fe18c1fd0f84e00e355c8; scriptul cere și include sursa exactă. |
| H-2 | Ridicată | REMEDIAT | MainActivity acceptă rtsps, validarea remote este extrasă și există ExternalPlaybackIntentInstrumentedTest cu Intent.ACTION_VIEW real. |
| M-1 | Medie | REMEDIAT | Dependența a fost eliminată; se folosește AES-GCM cu cheie neexportabilă generată direct în Android Keystore. |
| M-2 | Medie | REMEDIAT | Instanță application-scoped prin OxigenApp; attachBaseContext citește doar SharedPreferences; Keystore este preîncălzit pe Dispatchers.IO. |
| M-3 | Medie | REMEDIAT | compilerOptions.jvmTarget este fixat explicit la JVM_17; Java source/target rămân 17. |
| M-4 | Medie | REMEDIAT | API 37 pe telefon și Android TV; sunt testate PlayPlatformClassic și DirectFullNano pe TV; toate cele 8 combinații rămân în debug/release matrix. |
| M-5 | Medie | REMEDIAT CU LIMITARE PLATFORMĂ | HTTP public este refuzat înainte de conexiune și la fiecare redirect; ruta conectată trebuie să fie locală. Android Network Security Config acceptă reguli pe domenii, nu intervale RFC1918/CIDR, deci poarta platformei Direct rămâne activă pentru NAS arbitrar. |
| M-6 | Medie | REMEDIAT | Verificarea ZIP invocă scriptul real într-un arbore temporar; securitatea este verificată prin teste și invariants structurale, fără fișier temporar în rădăcină. |
| M-7 | Medie | REMEDIAT | Scriptul eșuează închis pentru .env, proprietăți de secrete, credentiale, token configs, keystore-uri și chei private; există test negativ. |
| M-8 | Medie | REMEDIAT | Clientul public folosește application interceptor și network interceptor; verificarea se execută la fiecare hop. |
| M-9 | Medie | REMEDIAT | 195 texte în res/values și 7 directoare values-*; localeConfig și LocaleManager pentru Android 13+; fallback intern pentru versiuni mai vechi. |
| L-1 | Scăzută | REMEDIAT | ollamaBearerToken folosește trim().take(MAX_SECRET_LENGTH). |
| L-2 | Scăzută | REMEDIAT | Sunt acceptați numai octeții PK 03 04; funcție pură și teste pentru 05 06 / 07 08. |
| L-3 | Scăzută | REMEDIAT | Parser numeric strict; forme întregi, hex/octal/necanonice sunt respinse; țintele private sunt blocate. |
| L-4 | Scăzută | REMEDIAT | Au fost adăugate uiMode, density, fontScale, keyboard/hidden, navigation, layoutDirection și colorMode. |
| L-5 | Scăzută | REMEDIAT | Checkpoint salvat; stopSelf când playerul nu redă și nu bufferizează cu playWhenReady. |
| L-6 | Scăzută | REMEDIAT | Politica este documentată în SECURITY.md și PRIVACY.md ca limită intenționată. |
| L-7 | Scăzută | REMEDIAT | Manifestul declară @xml/locales_config cu cele 8 limbi. |
| L-8 | Scăzută | REMEDIAT | RELEASE.md explică placeholderul fail-closed; update.json este actualizat la code 8/name 2.1 și rămâne enabled=false. |
| L-9 | Scăzută | REMEDIAT | Mesajele tehnice SubtitleSearchService au fost uniformizate în engleză; UI continuă să afișeze erori localizate sigure. |
| L-10 | Scăzută | REMEDIAT | Comentariile semnalate au fost uniformizate în engleză. |
| L-11 | Scăzută | REMEDIAT | Jobul debug urcă numai rapoarte, cu nume DO-NOT-DISTRIBUTE; APK-urile debug nu mai sunt încărcate. |
| L-12 | Scăzută | REMEDIAT | Comentariul descrie exact excluderea META-INF/proguard și păstrarea licențelor/notices. |
| L-13 | Scăzută | REMEDIAT CU POARTĂ EXTERNĂ | Dependency locking este activ; jobul dependency-baseline generează lockfiles și SHA-256 verification metadata pentru review. Baseline-ul real nu poate fi produs offline fără artefactele rezolvate. |
| L-14 | Scăzută | REMEDIAT | security.yml include CodeQL și dependency-review pin-uite la SHA; scan-secrets.py rulează în policy și CodeQL. |
| L-15 | Scăzută | REMEDIAT | Jobul ajustează /dev/kvm când există și folosește -accel auto. |

## Validări executate local

- `python3 scripts/verify-project-policy.py` — trecut;
- teste unitare Python pentru packagerul GPL — trecute;
- scanare secrete/private keys — trecută;
- parsare JSON, TOML, YAML și toate fișierele XML — trecută;
- compilare pură `UpdateSecurityPolicy.kt` — trecută;
- test executabil Kotlin pentru politica HTTP/LAN/RTSPS — trecut;
- parser Kotlin pentru source set-urile Play/Platform/Classic și Direct/Full/Nano — fără erori de sintaxă;
- pachet GPL testat pe proiectul real cu sursă NextLib de test — căi POSIX și manifest valide;
- arhiva finală verificată cu `unzip -t` și scanare de căi.

## Limitare de mediu

Buildul Android Gradle complet nu a putut fi executat aici. Wrapperul încearcă să descarce Gradle 9.6.1, dar mediul nu poate rezolva `services.gradle.org`, iar Android SDK nu este instalat. Aceasta nu este o dovadă de eroare în proiect, dar înseamnă că APK/AAB, lint și testele instrumentate trebuie confirmate de workflow-urile incluse sau într-un Android Studio/SDK 37 conectat.

## Poarta obligatorie înainte de publicare

1. Rulează workflow-urile `Android verification` și `Security analysis` fără erori.
2. Revizuiește și comite baseline-ul real `verification-metadata.xml` + lockfiles generat de CI.
3. Testează pe cel puțin un telefon și două dispozitive Android TV/Google TV fizice.
4. Pentru orice variantă `full`, publică arhiva de sursă corespunzătoare care include NextLib la commitul fixat.
5. Semnează numai cu cheia de producție păstrată în afara proiectului.

---

## Addendum 2026-10-08 — redenumirea aromei `ai`

Aromele dimensiunii `ai` au fost redenumite pentru a elimina confuzia dintre
nume și conținut: `classic` → **`api24`** (traducere standard ML Kit +
provideri, API 24+, fără motor AI on-device) și `nano` → **`genai`**
(adaugă motorul on-device Gemini Nano, API 26+). Redenumirea este pură
(etichete, foldere de source set și nume de variante Gradle); comportamentul,
dependențele și manifestele sunt neschimbate.

Consecințe pentru acest raport:

- Checklist-ul de atestare M-4 de mai sus rămâne obligatoriu, dar variantele
  sale se citesc acum astfel: `directFullClassicDebug` → `directFullApi24Debug`,
  `directPlatformClassicDebug` → `directPlatformApi24Debug`,
  `directFullNanoDebug` → `directFullGenaiDebug`,
  `directPlatformNanoDebug` → `directPlatformGenaiDebug`,
  `playPlatformClassicRelease` → `playPlatformApi24Release`,
  `directPlatformClassicRelease` → `directPlatformApi24Release`,
  `playPlatformNanoRelease` → `playPlatformGenaiRelease`,
  `directFullNanoRelease` → `directFullGenaiRelease`.
- Textul istoric al raportului (secțiunile datate) păstrează intenționat numele
  vechi, deoarece documentează build-uri și atestări făcute înainte de
  redenumire.
- După atestarea pe dispozitiv real, anexa `validation.json` va fi regenerată
  cu noile nume de variante.

