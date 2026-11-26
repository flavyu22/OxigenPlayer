# OxigenPlayer Pro 2.2.1 — Repair & Update Report

Date: 2026-09-19

## Scope

The uploaded OxigenPlayer source archive was unpacked without generated Gradle/IDE build caches, audited for its Android toolchain, dependency catalog, release metadata, CI workflow pins, local-machine files and repository policy checks. The repaired package keeps the existing application architecture and security model while replacing preview/stale build components with stable compatible releases.

## Updated toolchain and libraries

| Component | Previous | Repaired | Decision |
|---|---:|---:|---|
| Android Gradle Plugin | 9.5.0-alpha05 | 9.4.0 | Stable AGP release; removes preview dependency |
| Gradle wrapper | 9.7.1 | 9.7.1 | Already current stable; retained |
| Compose compiler/Kotlin plugin | 2.4.10 | 2.2.10 | Aligned with AGP 9.4 built-in Kotlin stable line |
| Compose BOM | 2026.08.00 | 2026.09.00 | Updated |
| AndroidX Media3 | 1.11.0 | 1.11.1 | Updated |
| Coil | 3.5.0 | 3.6.3 | Updated; includes AGP 9.4/R8 compatibility fix |
| Baseline Profile plugin | 1.5.0-rc02 | 1.5.0 | Stable release |
| Macrobenchmark | 1.5.0-rc02 | 1.5.0 | Stable release |
| UiAutomator | 2.3.0 | 2.4.0 | Updated |
| Tracing | 2.0.0 | 2.0.2 | Updated |
| GitHub setup-java | v5.6.0 SHA | v6.0.1 SHA | Updated and still SHA-pinned |
| GitHub CodeQL Action | 4.37.6 | 4.38.1 | Updated and still SHA-pinned |

Already-current versions such as AndroidX Core 1.19.0, Activity 1.13.0, Lifecycle 2.11.0, TV Material 1.1.0, OkHttp 5.5.0, kotlinx-coroutines-play-services 1.11.0, ML Kit Translate 17.0.3, Language ID 17.0.6, GenAI Prompt 1.0.0-beta4 and NextLib 1.10.1-0.13.0 were intentionally retained.

## Cleanup and consistency repairs

- Removed `local.properties`, which is machine-specific and must not be shipped or committed.
- Removed extracted `.artifacts` and Python `__pycache__` directories.
- Removed `app/gradle.lockfile` and `gradle/verification-metadata.xml` because they described the old AGP/dependency graph and would conflict with the upgraded catalog. The existing CI `dependency-baseline` job regenerates both against the new graph using `--write-locks --write-verification-metadata sha256`.
- Bumped application version to `versionCode 12` / `versionName 2.2.1` and aligned README, release instructions, source-package command, workflow source version and disabled updater metadata.
- `update.json` intentionally remains `enabled: false`; its hash and size stay empty/zero until the actual signed APK is published.

## Validation performed offline

The repaired tree is validated with the repository's own policy/security/unit-test scripts plus independent TOML/JSON/XML parsing and Python bytecode compilation. A full Android Gradle compilation requires Android SDK/Maven artifact resolution and JDK 25 in a build environment; the package therefore does not claim a locally produced APK. The GitHub Actions matrix remains configured to compile/lint/test every product combination and to regenerate dependency integrity metadata.

## Release integrity note

After first CI resolution of the new graph, review the generated `app/gradle.lockfile` and `gradle/verification-metadata.xml`, then commit the reviewed baseline if your release process requires locked dependency state in source control. Do not restore the old files from the uploaded archive.
