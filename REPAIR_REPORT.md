# OxigenPlayer Pro 2.1.2 — audit and repair, 7 August 2026

This supersedes the previous one-paragraph repair summary. It records what was verified,
what was found, what was changed, and what could not be checked in this environment.

## Verification performed

No Android SDK, Gradle distribution or Maven access was available, so a Gradle build could
not be run. The following was executed instead:

| Check | Result |
|---|---|
| Kotlin parse of `main` + all four flavour source sets (75 files, 14 739 lines) | no syntax errors |
| Unit tests for the Android-free core, compiled and executed against stubs | 55 passed, 0 failed |
| XML well-formedness, every file in the tree | clean |
| String parity across the 8 shipped locales | 193/193 in each, no format-argument drift |
| `R.string` references vs. definitions | no missing, no unused |
| `scripts/verify-project-policy.py`, `scripts/scan-secrets.py` | pass |
| `python3 -m py_compile scripts/*.py` | pass |
| New CI checks, deliberately broken to confirm they fire | both detected the injected fault |

The executed tests cover `SeekMath`, `TimelineMath`, `PlaybackState`, `PlaybackUriPolicy`,
`SubtitleParser`, `SubtitleTimeline`, `RefCountedLruPool`, `UpdateSecurityPolicy`,
`TranslationProviderConfig`, `SubtitleProviderConfig`, `ProfessionalPlayback`,
`MediaTitleParser` and `OxigenStrings`. `SubtitleSafetyTest`, `NetworkAddressPolicyTest` and
`LocalMediaLibraryTest` need OkHttp or the Android framework and were not executed.

## Defects fixed

**1. Media notification suppressed on Android 13+ — `AndroidManifest.xml`, `MainActivity.kt`**

`POST_NOTIFICATIONS` was declared nowhere in the project. `PlaybackService` is a
`MediaSessionService` running as a `mediaPlayback` foreground service, so on API 33+ its
notification was dropped and users lost the lock-screen and shade transport controls. The
permission is now declared and requested once, when the first item is loaded rather than at
cold launch.

**2. Server-side resume checkpoints were a silent no-op — `PlaybackService.kt`**

`persistPlaybackCheckpoint()` read `currentMediaItem.localConfiguration.uri`. That field is
stripped when a `MediaItem` crosses the session boundary, so for every item set through the
`MediaController` the URI was null and the 10-second checkpoint returned early without
saving. `MainActivity` already had the correct three-step fallback; it is now shared as
`Player.currentPlaybackMediaUri()` and used on both sides.

**3. Stored API keys lost on cold start — `PreferencesManager.kt`**

`secrets()` returned the Keystore store only if the background `prewarmSecureStorage()` had
already finished. `PlayerViewModel` reads the provider configuration in its constructor,
during the first composition, which normally precedes that coroutine. Reads therefore fell
back to the empty in-memory store, and writes reached only that store — so a key saved in
that window was gone after the next launch. The store is now initialized on demand; the
prewarm remains, purely to keep first-run key generation off the caller's thread.

**4. Buffer tiers collapsed on low-RAM televisions — `DevicePerformance.kt`**

`veryLowRam` was `isLowRamDevice || totalRamMb <= 2048`, which made it true whenever `lowRam`
was true. Every device with the low-RAM flag received the 5 s/15 s buffers intended for 2 GB
hardware, so 3 GB boxes rebuffered more than the tiering intended. `veryLowRam` now depends
on measured memory only.

**5. Predictive back left implicit — `AndroidManifest.xml`**

With `targetSdk = 37` and an always-enabled `OnBackPressedCallback`, the back behaviour
depended on the platform default. `android:enableOnBackInvokedCallback="true"` is now
declared, which is what the AndroidX dispatcher in use here expects.

**6. Invalid SRT timestamps possible on export — `SubtitleEngine.kt`**

`formatSrtTime` did no lower-bound check, so a negative time would render as
`00:00:00,-500` and break any consumer of the exported file. Clamped at zero.

**7. Smaller corrections**

- `MainActivity.onCreate` mutated `window.attributes.preferredDisplayModeId` without
  dispatching the change, and assigned the default value; removed.
- The wall-clock ticker in the top bar ran every 30 s regardless of lifecycle state; it is
  now gated on `lifecycleStarted`, like the position poller already was.
- `PlayerViewModel.onCleared()` did not call through to `super`.
- `seek_backward` was translated into all 8 locales but referenced nowhere — the seek
  affordance is the slider, which has its own description. Removed from all locales.

## Open items after the first pass

All four were resolved in the second pass below; they are kept here for the record.

- **`OxigenStringsTest` cannot fail.** `OxigenStrings.keysFor()` returns the same key set for
  every supported language, so the parity assertion is a tautology. Real parity is only
  verified externally. The test should compare rendered resources per locale, which needs an
  instrumented or Robolectric runner.
- **Picture-in-Picture is hard-coded to 16:9.** `buildPictureInPictureParams` ignores the
  actual video aspect, so 2.39:1 material is letterboxed in the PiP window. Fixing this
  properly means carrying `videoSize` through `PlaybackSnapshot`.
- **`res/raw/gpl_3_0.txt` and `full/res/raw/nextlib_notice.txt` are unreferenced.** Either
  wire them into the About dialog, which is the usual way to satisfy the display obligation,
  or accept them as unused payload. Left in place deliberately — removing licence text is not
  a decision an audit should make silently.
- **`Modifier.tvFocusable` uses `composed {}`**, which blocks skipping and is discouraged in
  current Compose. Migrating to `Modifier.Node` would cut recomposition cost on TV grids.
- **`allowBackup="false"` coexists with `fullBackupContent` and `dataExtractionRules`**, which
  are ignored in that configuration. Harmless, kept as defence in depth.

## Second pass

A further review covered the files not read in full during the first pass:
`LocalMediaLibrary`, `FilePickerManager`, `RefCountedLruPool`, `NetworkClient`,
`UpdateManager`, `SubtitleEngine`, the diagnostics overlay and the whole `ui` package.

**8. Picture-in-Picture ignored the video geometry — `PlaybackState.kt`, `MainActivity.kt`**

`buildPictureInPictureParams` requested a fixed 16:9 window, so 2.39:1 films and 4:3 recordings
were letterboxed inside the floating window. `PlaybackSnapshot` now carries the decoded width,
height and pixel aspect ratio; the ratio is derived from those, clamped to the roughly
1:2.39 .. 2.39:1 range the platform accepts, and passed through. Anamorphic content is handled,
which the stored resolution alone gets wrong. Six new unit tests cover the arithmetic.

**9. GPL notices were shipped but never shown — `AboutDeveloperDialog.kt`**

`res/raw/gpl_3_0.txt` and, in the full flavor, `full/res/raw/nextlib_notice.txt` were present in
the APK but referenced from nowhere, so the binary conveyed no licence to its users. The About
dialog now shows the SPDX identifier and the corresponding-source repository, and loads the
licence text on demand off the main thread. The NextLib notice is resolved through a new
flavor-specific `CodecLicenseNotice`, following the existing `CodecRenderersFactory` pattern, so
the platform flavor does not claim to bundle decoders it does not link.

**10. The speech recognizer language was silently ignored — `SubtitleSearchDialog.kt`**

`putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())` passed a `Locale` where the
platform reads a `String` with `getStringExtra`, so voice search always fell back to the system
default instead of the requested language. Now sends `toLanguageTag()`.

**11. The supported-language list existed in four places — several files**

`OxigenStrings.supportedLanguages` duplicated `PreferencesManager.SUPPORTED_APP_LANGUAGES`, and
both could drift from `locales_config.xml` and the Gradle `localeFilters`. Drift is invisible at
build time and shows up as a language that is selectable but has no packaged resources. The
facade now delegates to the single constant, and `scripts/verify-project-policy.py` gained
`verify_language_sources_agree`, which cross-checks all remaining declarations.

**12. String lookups are now verified — `scripts/verify-project-policy.py`**

`OxigenStrings.get` takes a string key and returns that key verbatim when it is unmapped, so a
typo puts a raw identifier such as `subtitle_read_failed` on screen and nothing at build time
notices. `verify_string_lookup_keys` now checks every literal call site against the lookup table
and asserts that key names match their resource names. Both new checks were tested by injecting a
fault and confirming a non-zero exit.

**13. `remember` was called from a conditional position — `PlayerDialogsOverlay.kt`**

The subtitle-search branch used `subtitleSearchService ?: remember { ... }`, which claims a
composition slot only on some passes. Restructured to remember unconditionally and then choose.

**14. The tautological test was replaced — `OxigenStringsTest.kt`**

`keysFor` returns the same keys for every supported language, so the old assertion could not
fail. Replaced with four checks that can: single-definition of the language set, empty keys for
unsupported tags, verbatim return for an unknown key, and key-format validity.

## Reviewed and deliberately left alone

- **Derived OkHttp clients are not a leak.** `SubtitleSearchService` builds three clients with
  `NetworkClient.instance.newBuilder()`, which shares the parent's dispatcher, connection pool
  and cache. The per-dialog instance is a thin wrapper, not a new thread pool, so no disposal
  hook was added.
- **`Modifier.tvFocusable` still uses `composed {}`**, which blocks skipping and is discouraged
  in current Compose. Migrating it to `Modifier.Node` is a behavioural rewrite of the focus
  visuals on every TV card; without a compiler or a device to verify against, doing it here
  would be guesswork. It is a performance refinement, not a defect.
- **Sixteen lookup keys are mapped but never displayed**, including `resume_movie_title`,
  `resume_movie_question`, `resume_btn` and `restart_btn` — an entire "resume where you left
  off?" prompt. The app still stores checkpoints and resumes silently, so either the dialog was
  dropped in a refactor or the prompt was intentionally removed. Deleting 128 translated entries
  is a product decision, so they were left in place. The others are `about_dev_text` (a duplicate
  of `developed_by`), `done_btn`, `grant_storage_permission`, `language_settings`,
  `load_subtitles`, `save_error`, `saved_to`, `select_media`, `style_settings`, `translating`,
  `translation_settings` and `video_subtitles`.
- **`allowBackup="false"` coexists with `fullBackupContent` and `dataExtractionRules`**, which
  are ignored in that configuration. Kept as defence in depth.

## Still required before release

A full Gradle build against Android SDK 37, `lint`, the instrumented tests, and signing
remain outside what could be done here. See `FINAL_AUDIT_REPORT.md`, `SECURITY.md` and
`RELEASE.md` for the external release gates.
