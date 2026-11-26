
# OxigenPlayer Pro 2.2.0 — Audit complet

**Date :** 24 août 2026
**Version auditée :** `versionCode 11`, `versionName 2.2.0` (README 2.2.0, `update.json` 2.2.0 — cohérents)
**Type d'audit :** statique (structure, configuration de build, sécurité, CI/CD, tests, conformité licence)
**Verdict global :** ✅ **SAIN — prêt pour la chaîne de vérification CI. Aucun blocant détecté en analyse statique.**

---

## 1. Inventaire du projet

| Élément | Valeur |
|---|---|
| Fichiers totaux du workspace | ~2 398 (~466 Mo, dont `build/`, `.gradle/` locaux) |
| Fichiers Kotlin (`app/src`) | 76 (52 principaux + 20 tests unitaires + 4 tests instrumentés) |
| Lignes Kotlin | ~14 500 |
| Méthodes `@Test` | 108 |
| Langues | 8 (en, ro, es, fr, de, it, pt, ru) via `values-*` + `localeConfig` |
| Variantes de produit | 8 = {play, direct} × {platform, full} × {classic, nano} |
| minSdk / targetSdk / compileSdk | 24 (26 pour `nano`) / 37 / 37 |
| Toolchain | AGP 9.3.1, Kotlin 2.4.10, Gradle 9.7.0 (wrapper checksummé SHA-256), JDK 25 épinglé, bytecode Java 17 |
| Modules | 1 (`:app`) |

## 2. Architecture et organisation

- Code principal sous `app/src/main/java/com/oxigenplayer/app/` : lecture (`PlaybackService`, `PlayerViewModel`, `ExoPlayerHelper`), sous-titres/traduction (`SubtitleParser`, `SubtitleEngine`, `TranslationManager`), sécurité (`PlaybackUriPolicy`, `UpdateSecurityPolicy`, `AndroidKeystoreSecretStore`, `NetworkClient`), performance (`DeviceCapabilities`, `RefCountedLruPool`), UI Compose sous `ui/`.
- Séparation propre logique pure / Android : `UpdateSecurityPolicy.kt`, `PlaybackUriPolicy.kt`, `TimelineMath.kt`, `SeekMath.kt` sont testables hors Android — couverts par des tests JVM.
- Overlays de flavor bien structurés (`app/src/direct/AndroidManifest.xml` ajoute `REQUEST_INSTALL_PACKAGES`, cleartext LAN explicite avec `tools:replace`).

## 3. Sécurité — points forts vérifiés dans le code

1. **Stockage des clés fournisseur** (`AndroidKeystoreSecretStore.kt`) : AES-256-GCM, clé non exportable Android Keystore, IV aléatoire par chiffrement, AAD liée au nom de clé + package, versionnement de format, suppression fail-closed au moindre défaut de déchiffrement.
2. **Politique de mise à jour** (`UpdateSecurityPolicy.kt`) : allowlist stricte d'hôtes GitHub, regex anti path-traversal/dot-segments, HTTPS uniquement (pas de userinfo/fragment/query arbitraire), SHA-256 obligatoire (64 hex), taille bornée à 350 Mo, vérification du magic ZIP `PK\x03\x04`. `update.json` actuel : `enabled=false`, `sha256=""` → placeholder fail-closed conforme.
3. **Cleartext HTTP — défense en profondeur** :
   - manifeste principal `usesCleartextTraffic=false` + `network_security_config.xml` strict ;
   - flavor `direct` réactive le cleartext au niveau plateforme uniquement (`tools:replace`) ;
   - gate applicatif `BuildConfig.CLEARTEXT_LAN_ENABLED` ;
   - OkHttp : application interceptor **et** network interceptor → contrôle à chaque hop/redirect, avec vérification de l'adresse réelle de la route (`isLocalNetworkAddress`). Le Play build refuse tout HTTP ; le Direct build limite aux cibles LAN explicites.
4. **Deep link `oxigenplayer://`** : l'URL transportée passe par la même politique `isSupportedRemotePlaybackUri` (test instrumenté dédié). Pas de contournement de schéma/credential/cleartext.
5. **Sauvegardes** : `allowBackup=false`, `backup_rules.xml` et `data_extraction_rules.xml` excluent tout (root/file/database/sharedpref/external) → les credentials chiffrées ne quittent pas l'appareil.
6. **Secrets de build** : BuildConfig provider vide partout par défaut ; embed uniquement en debug local avec opt-in double ; release signée échoue si les variables `OXIGEN_RELEASE_*` manquent (fail-closed sur `validateSigning*Release`).
7. **Permissions** minimales et justifiées ; `READ_EXTERNAL_STORAGE` plafonné à SDK 32 ; pas de permission dangereuse superflue.
8. **CI sécurisée** : actions épinglées par SHA complet, `persist-credentials: false`, permissions minimales, scan de secrets + checksum wrapper + policy à chaque run, CodeQL hebdomadaire, dependency-review sur PR.


### Constatations (aucune critique)

| ID | Sévérité | Constatation | Détail / recommandation |
|---|---|---|---|
| A-1 | Info (attendu local) | `local.properties` présent → `scripts/scan-secrets.py` échoue en local, donc `verify-project-policy.py` aussi. | Ne contient que `sdk.dir` ; ignoré par `.gitignore`. En CI le fichier n'existe pas → pas d'impact. Pour travailler localement : renommer temporairement ou ajuster `IGNORED` du scanner. |
| A-2 | Moyenne (porte externe connue) | `gradle/verification-metadata.xml` absent ; aucun `*.lockfile` committé alors que le dependency locking est actif. | Conforme à la porte L-13 documentée : le job CI `dependency-baseline` génère locks + métadonnées SHA-256. **Action requise avant release : générer, relire et committer ces fichiers.** |
| A-3 | Faible | `update.json` publié avec `sha256` vide et `sizeBytes=0`. | Intentionnel (désactivé), mais tout remplissage doit passer par `scripts/generate-update-metadata.py`, jamais manuellement. |
| A-4 | Faible | `PlaybackService` exporté (`exported="true"`). | Requis par Media3 ; SECURITY.md documente l'acceptation des seuls contrôleurs de confiance. À revérifier après chaque montée de version Media3. |
| A-5 | Info | Workspace **non versionné** : pas de dépôt Git local (`.git` absent). | Impossible de vérifier localement ce qui serait « committé ». Initialiser/pousser vers le dépôt distant pour activer la chaîne CI décrite. |
| A-6 | Info | `.artifacts/` contient un plan d'implémentation résiduel. | Ignoré par `.gitignore` ; suppression sans risque possible. |

### Éléments revus et jugés corrects

- `parseStrictNumericAddress` : parsing IPv4 strict (pas d'octal/hex, zéros non significatifs refusés).
- `isSupportedRemotePlaybackUri` : pas de userinfo/fragment, port 1–65535, labels DNS validés, IPv6 validé par `URI`.
- `AndroidKeystoreSecretStore` : opérations synchronisées, contexte application, préchauffage Keystore sur IO.
- Manifeste : PiP, `configChanges` exhaustifs, banner TV, FileProvider non exporté avec chemins restreints.

## 4. Qualité et tests

- **20 suites de tests unitaires JVM** couvrant les modules critiques purs : politique URI, politique de mise à jour, parser SRT/VTT/ASS, timeline/seek, pool LRU refcounted, A-B repeat, config fournisseurs, sécurité sous-titres, adresse réseau.
- **4 suites instrumentées** dont `ExternalPlaybackIntentInstrumentedTest` (chemin réel ACTION_VIEW incluant rtsps) et tests TV.
- CI exécute : tests+lint+assemble pour les 8 variantes debug, bundle/assemble signé (clé CI jetable) pour les 8 variantes release, tests connectés sur émulateurs téléphone **et** Android TV API 37.
- `lint { abortOnError = true; checkReleaseBuilds = true; checkDependencies = true }`.

## 5. Conformité licence

- GPL-3.0-only racine + SPDX sur les sources + licence embarquée (`res/raw/gpl_3_0.txt`).
- Variantes `full` : NextLib épinglé (`1.10.1-0.13.0` / commit `773c841f…`) avec packaging de source correspondante (`scripts/package-gpl-source.py` + tests + checksum `.sha256`).
- Rappel : toute distribution publique d'une variante `full` doit publier l'archive de source correspondante.

## 6. Validations exécutées pendant cet audit

**Statique :**
- `py scripts/verify-project-policy.py` → **PASSÉ intégralement** (exit 0) une fois `local.properties` écarté du scanner ; tous les contrôles (wrapper checksum, manifestes, intercepteurs réseau, localisation, CI) sont conformes.
- Lecture intégrale des fichiers critiques : manifests (main + direct), network security config, règles backup/extraction, politiques URI/update, keystore store, NetworkClient (intercepteurs), build.gradle.kts, libs.versions.toml, workflows CI, scripts Python.
- Balayages regex TODO/FIXME et secrets : aucune fuite réelle.

**Dynamique (exécution réelle — JDK 25 JBR + Gradle 9.7.0) :**

| Validation | Résultat |
|---|---|
| `testPlayPlatformClassicDebugUnitTest` + `testDirectFullNanoDebugUnitTest` (ré-exécution forcée) | ✅ BUILD SUCCESSFUL — **202 tests, 0 échec, 0 erreur, 0 ignoré** |
| `lintPlayPlatformClassicRelease` (`abortOnError=true`) | ✅ BUILD SUCCESSFUL — **0 erreur, 54 warnings mineurs** |
| Recorrection appliquée puis re-test | ✅ Tests re-passés après modification |

**Corrections appliquées pendant l'audit :**
1. `local.properties` : chemin SDK échappé au format properties (`C\:/…`) — corrigeait l'erreur lint `PropertyEscape` locale (fichier non committé ; CI non affectée).
2. `PlayerViewModel.kt` : suppression de l'appel `super.onCleared()` inutile — corrige le warning lint `EmptySuperCall`.

**Warnings lint restants (54, non bloquants) :** `InlinedApi` sur les permissions MediaStore API 33/34 (chemins gardés par versioning), `TypographyEllipsis` (« ... » vs « … » dans les strings localisées), `UseKtx` (`Uri.parse` → `toUri`). Aucun risque fonctionnel ou de sécurité.


## 7. Limites de l'audit

- Compilation, tests unitaires et lint **exécutés avec succès** localement (voir section 6). Non exécuté : les 6 autres variantes debug/release, le bundle AAB signé, les tests instrumentés sur émulateur et les tests connectés — couverts par les workflows CI inclus.
- Absence de dépôt Git local → impossible de différencier fichiers suivis/non suivis.
- `verify-project-policy.py` échoue en local tant que `local.properties` existe à la racine (comportement attendu du scanner ; CI non affectée).

## 8. Portes obligatoires avant publication

1. Passer les workflows **Android verification** et **Security analysis** au vert sur `main`.
2. Générer, relire puis committer `gradle/verification-metadata.xml` + lockfiles (job `dependency-baseline`) — A-2.
3. Remplir `update.json` via `generate-update-metadata.py` au moment de publier v2.2.0, puis basculer `enabled=true`.
4. Tester sur ≥1 téléphone et ≥2 TV Android physiques (décodeurs, HDMI, télécommande, USB/NAS, installeur Direct).
5. Signer exclusivement avec la clé de production externe au projet.
6. Pour variante `full` : publier l'archive de source correspondante NextLib.

---
*Audit statique complet + validation dynamique (tests unitaires et lint exécutés avec succès). Deux corrections mineures appliquées : `local.properties` (échappement properties) et `PlayerViewModel.kt` (appel super redondant).*
