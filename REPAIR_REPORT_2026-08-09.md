# OxigenPlayer Pro 2.1.2 — full local build repair, 9 August 2026

This pass completes what the previous audits could not do: a **real Gradle build, unit-test
run and lint pass on this machine**. All defects found during that verification were fixed
in-tree. Every result below was produced locally with Android SDK 37, the Android Studio JBR
(OpenJDK 25) and the checked-in Gradle 9.6.1 wrapper.

## Environment setup

| Item | State |
|---|---|
| `local.properties` | created, points to `%LOCALAPPDATA%\Android\Sdk` |
| JDK | Android Studio JBR (OpenJDK 25.0.3), matches `gradle/gradle-daemon-jvm.properties` (toolchain 25) |
| SDK platforms | android-37.0 installed (matches `compileSdk = 37`) |
| Build tools | 37.0.0 installed |

Builds must be run with `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`; the system
`java.exe` is 1.8 and too old for Gradle 9 / AGP 9.

## Verification results (this pass)

| Check | Result |
|---|---|
| `gradlew assembleDebug` (all 8 product combinations) | **BUILD SUCCESSFUL**, 8 APKs in `app/build/outputs/apk/*/debug/` |
| Kotlin/Java compiler warnings | **0** (all 7 previous warnings fixed, see below) |
| Unit tests, all 8 debug variants (`test<Variant>UnitTest`) | **664 executions, 0 failures, 0 errors** (83 tests × 8 variants) |
| `gradlew lintPlayPlatformClassicRelease lintDirectPlatformClassicRelease` | **BUILD SUCCESSFUL**, 0 errors (abortOnError=true) |

APKs produced:

```
app/build/outputs/apk/directFullClassic/debug/app-direct-full-classic-debug.apk
app/build/outputs/apk/directFullNano/debug/app-direct-full-nano-debug.apk
app/build/outputs/apk/directPlatformClassic/debug/app-direct-platform-classic-debug.apk
app/build/outputs/apk/directPlatformNano/debug/app-direct-platform-nano-debug.apk
app/build/outputs/apk/playFullClassic/debug/app-play-full-classic-debug.apk
app/build/outputs/apk/playFullNano/debug/app-play-full-nano-debug.apk
app/build/outputs/apk/playPlatformClassic/debug/app-play-platform-classic-debug.apk
app/build/outputs/apk/playPlatformNano/debug/app-play-platform-nano-debug.apk
```

## Defects found and fixed in this pass

**1. SSRF bypass in the public-URL policy — `NetworkClient.kt` (security)**

`NetworkAddressPolicyTest.allowsOnlyHttpsUrlsWithoutPrivateNumericTargets` failed when the
test suite was actually executed: `https://127.1/video.m3u8` was accepted. OkHttp keeps the
abbreviated IPv4 host `127.1` as a literal, `parseStrictNumericAddress` only accepts strict
four-part dotted quads, and the fallback treated the host as an ordinary DNS name. System
resolvers expand `127.1` to `127.0.0.1` at connect time, so the shared client could have been
pointed at the loopback interface despite the SSRF policy. Hosts made only of digits and dots
that fail strict parsing are now rejected outright (fail closed). This test had never been
executed in the previous audit because it needs OkHttp on the classpath.

**2. Theme resource cycle — `values/themes.xml`, `values-v27/themes.xml` (lint error)**

`Theme.OxigenPlayer` declared `Theme.OxigenPlayer.Base` as its parent, while the implicit
dot-parent rule makes `Theme.OxigenPlayer` the implied parent of `Theme.OxigenPlayer.Base` —
a potential cycle flagged as `ResourceCycle` (fatal with `abortOnError`). The base style was
renamed to the standard `Base.Theme.OxigenPlayer` form, which cannot participate in implicit
parent lookup.

**3. Invalid session error constant — `PlaybackService.kt` (lint error)**

`onCustomCommand` returned `SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)`, which is
no longer in the allowed constant set of Media3 1.10 (`WrongConstant`). Replaced with
`SessionError.ERROR_NOT_SUPPORTED`.

**4. Missing `android:exported` in the Direct flavor manifest — `direct/AndroidManifest.xml`
(lint error)**

The flavor overlay re-declares `.MainActivity` with an `ACTION_VIEW` intent filter for
cleartext LAN media but did not set `android:exported`, which is mandatory from Android 12
(`IntentFilterExportedReceiver`). Set to `true`, matching the exported main-manifest activity
that handles external VIEW intents.

**5. Deprecated `LocalLifecycleOwner` — `MainActivity.kt` (compiler warning)**

Migrated from the deprecated `androidx.compose.ui.platform.LocalLifecycleOwner` to
`androidx.lifecycle.compose.LocalLifecycleOwner`; added the explicit
`androidx.lifecycle:lifecycle-runtime-compose` dependency to the version catalog and the app
module.

**6. Redundant `else` branches in exhaustive `when` — `MainActivity.kt` (compiler warnings)**

Removed the dead `else` arms in the `SubtitleFileResult` and `UpdateCheckResult` `when`
expressions; both sealed hierarchies are fully covered by the explicit branches.

**7. Override parameter name mismatch — `NetworkClient.kt` (compiler warning)**

`awaitResponse`'s `Callback.onFailure` named the parameter `error` while the supertype names
it `e`; renamed to match.

**8. Deprecated `TRIM_MEMORY_RUNNING_CRITICAL` — `OxigenApp.kt` (compiler warning)**

The constant is deprecated from API 34 (the RUNNING_* levels are no longer reported there).
Replaced with a documented raw-value constant (`15`) so pre-34 behaviour is preserved without
the deprecation warning.

**9. Dead Elvis operator — `UpdateManager.kt` (compiler warning)**

`val activeConnection = connection ?: return@withContext ...` applied `?:` to a smart-cast
non-null `HttpURLConnection`; the dead branch was removed.

## Build-failure that was environmental, not a code defect

The first multi-variant `assembleDebug` failed with
`FileSystemException: classes2.dex ... being used by another process` and a corrupted
`mergeDirectFullNanoDebugResources` state. Cause: two Gradle builds ran concurrently against
the same build directory (a leftover daemon from an interrupted run plus the new build).
Resolution: `gradlew --stop`, `gradlew clean`, single build. Not a project defect — but never
run two Gradle invocations in this tree at the same time.

## Remaining release gates (unchanged from RELEASE.md)

- Release signing requires `OXIGEN_RELEASE_STORE_FILE`, `OXIGEN_RELEASE_STORE_PASSWORD`,
  `OXIGEN_RELEASE_KEY_ALIAS`, `OXIGEN_RELEASE_KEY_PASSWORD` outside source control; release
  APKs/AABs were therefore not produced here (debug signing only).
- Dependency locking is enabled but no lockfiles are committed; generate and review them with
  `gradlew dependencies --write-locks` (or the CI baseline job) before publication.
- Instrumented tests (`connectedAndroidTest`) need a device/emulator and were not run.
- The 65 remaining lint **warnings** are informational (newer dependency versions available,
  typography ellipsis suggestions, unused launcher-channel resources, InlinedApi notes) and do
  not block the build.

## How to reproduce

```bat
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
gradlew.bat assembleDebug
gradlew.bat testDirectFullClassicDebugUnitTest testDirectFullNanoDebugUnitTest ^
          testDirectPlatformClassicDebugUnitTest testDirectPlatformNanoDebugUnitTest ^
          testPlayFullClassicDebugUnitTest testPlayFullNanoDebugUnitTest ^
          testPlayPlatformClassicDebugUnitTest testPlayPlatformNanoDebugUnitTest
gradlew.bat lintPlayPlatformClassicRelease lintDirectPlatformClassicRelease