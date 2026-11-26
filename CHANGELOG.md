## 2.2.9 — 18 November 2026

versionCode 20 / versionName 2.2.9. `update.json` stays `enabled: false` until a signed APK is published.

### Performance (26 November 2026)
- **The player screen no longer recomposes 4×/s during playback.** `bufferedPosition` lived in `PlayerUiState`, and the position poller copied the state on every tick while a stream was loading, which recomposed the whole `PlayerScreen` root scope. It is now a separate `PlayerViewModel.bufferedPosition` flow read only from the timeline's draw phase, like `currentPosition`.
- **Cheaper position checkpoints.** The 10 s checkpoint hashed the URI with SHA-256 and 32 `String.format` calls, then rewrote both the preferences and the title-recency XML files. Hex hashes are now memoised (last 8 inputs) with a lookup table, and a title's recency timestamp is rewritten at most once per hour (LRU eviction only needs coarse ordering).
- **"Translate all" progress is published once per 12-cue chunk** instead of once per cue; each emission recomposed the player screen, and cached or ML Kit cues finish in microseconds.
- **Subtitle search regexes are precompiled.** `normalizeForMatch` ran per search result and compiled four patterns on every call; language/ID filters compiled one per element. They are now file-level constants, and the two language-code filters in `ExoPlayerHelper`/`MainActivity` use a plain character check.
- **A failing hardware decoder no longer forces everything onto software.** On a Kirin 810 the HiSilicon HEVC decoder failed on the first frame of an MKV from TorrServer (`0x80001001`). Recovery used to rebuild the player FFmpeg-only, which kept every later file (H.264 included) on software decoding and recreated the media session. Now the failing MIME type is hidden for the rest of the process (`PlatformDecoderBlocklist` + a filtering `MediaCodecSelector`) and the same player is re-prepared: track selection picks FFmpeg for that type only, the session is untouched, and other codecs stay on hardware. The full FFmpeg-only rebuild remains as a second attempt. Verified on the tablet: first frame 0.38 s after the decoder error.
- **Root cause of that HEVC failure, and a diagnostic for it.** The MKV's `hvcC` is HEVC Main 10 (10-bit, level 4.0) with no VPS/SPS/PPS arrays, so Media3 cannot see the profile (`codecs=null`, reported as 8-bit) and selects the hardware decoder, which then logs `HEVC_DecSPS: SPS[0] decode error`. The HiSilicon decoder (and `c2.android.hevc.decoder`) declare only the Main profile, so FFmpeg is the only decoder that can play such files on this device. `PlaybackVideoDiagnostics` now logs each platform decoder's declared profile/level pairs once per MIME type (`decoder caps:` in `adb logcat -s OxigenPlayer-Video`).
- **Weak TVs explain a stuttering picture.** When the video decoder drops more than 20% of frames in two consecutive 5 s windows (at least 30 frames each), the player shows once per renderer session, in the app language, that the device cannot decode this video smoothly and suggests a lighter version (H.264/x264 or HEVC 8-bit, 1080p or lower). Typical trigger: an entry-level 4x Cortex-A53 TV falling back to FFmpeg for HEVC 10-bit. Logic in `DecoderLoadMonitor` (covered by `DecoderLoadMonitorTest`), detection in `PlaybackVideoDiagnostics`, string `decoder_overloaded` in all 8 languages. Checked on the tablet that smooth FFmpeg playback does not trigger it; the warning itself has not yet been seen on a weak device.
- **Buffer diagnostics work for progressive torrent streams.** `lastLoad` is never measured for MKV/MP4 over HTTP (one load spans the whole file), so every episode said "bitrate unknown". Each episode now ends with a fill rate (media buffered ÷ wall time); below 1.0x the source cannot keep up. On the tablet a TorrServer stream with 2 active peers measured 0.10x after a seek, matching TorrServer's own 148 KB/s for a ~3.1 Mbps file.
- Verified on a Huawei MatePad (BAH3-L09, debug build): HLS test stream played on the hardware AVC decoder, first frame about 1.2 s after the intent, adaptive switch to 1080p, no buffering episodes, no crash; with the controls hidden the UI rendered 0 frames over 15 s of playback.

### Review fixes (18 November 2026)
- **Per-title memory is bounded.** Playback position, picture framing and subtitle offset were written for every file or stream ever opened and never removed. Writes are now tracked in a small recency index (`oxigen_title_recency`); past 1,500 per-title keys (about 500 titles) the least recently used are dropped down to 1,200. Keys from older versions count as oldest. A position of 0 (finished or unstarted title) is no longer stored at all. Covered by `TitleKeyEvictionTest`.
- **Stored zoom/pan are validated on load**: per-title zoom is clamped to 1-3x and pan to -1..1; `NaN`/infinite values fall back to the defaults.
- **Buffer target bounded by the app heap.** ExoPlayer keeps buffered media in Java byte arrays, so the fixed 256 MB target for devices with more than 3 GB RAM could exceed the 192-256 MB heap of a typical TV without `largeHeap` and crash high-bitrate playback with `OutOfMemoryError`. The target is now at most 40% of `Runtime.maxMemory()`.
- **Software-decoder recovery no longer releases the player inside its own error callback**; the rebuild is posted to the main looper and skipped if the player was already replaced. Pending posts are cleared in `onDestroy`.
- **Android 7-9 media scan ran twice**: the aggregate `external` volume was queried as the only volume and again as the fallback. It is now queried once.
- Subtitle download error handling catches `Exception` instead of `Throwable`, so `OutOfMemoryError` and other fatal errors are not turned into a dialog message.
- **Real SAMI (`.smi`) subtitles now load.** Only SubRip-style `.smi` files with `-->` timings were handled; genuine SAMI documents (`<SYNC Start=ms>` cues) were detected as SMI and then always failed with "no valid cues". `<SYNC>` cues are now parsed, `&nbsp;` clear-screen markers are skipped, HTML whitespace is collapsed and `<br>`/`<P>` become line breaks. Extension-less SAMI files are detected by their `<SAMI>` root.
- **TTML/DFXP tick and frame timings.** `begin="15000000t"` with `ttp:tickRate` (the usual form in DFXP files from streaming services), `75f` frame counts and `hh:mm:ss:ff` clock times were rejected, so such files had no cues. They are now converted using the declared `ttp:tickRate` / `ttp:frameRate` (TTML defaults otherwise). Covered by `SubtitleSamiTtmlTimingTest`.
- **Subtitle archives**: `.smi`, `.sami`, `.ttml` and `.dfxp` entries inside a downloaded ZIP are now picked up, matching the formats the parser reads.
- **A cue could stay blank during translation.** When the prefetch worker cancelled a stale prefetch the visible cue was waiting on, the cue job treated that as its own cancellation and returned after having already cleared the text. It now falls back to a direct translation unless the cue job itself was cancelled.
- **`file://` intents into the app's private storage are rejected.** An external `ACTION_VIEW` could point the player at files under the app's data directory; such paths (compared per path segment after canonicalisation) are now refused like other disallowed media. Covered by `PrivateStoragePathTest`.
- **System bars auto-hide again on phones and tablets.** The status and navigation bars were hidden only in `onCreate`/`onResume`. App dialogs are separate Compose `Dialog` windows: opening one brought the bars back and closing it did not hide them, and the same happened after the keyboard, the notification shade or a permission prompt, so after the first dialog the bars stayed for the rest of the session. The player now re-hides them whenever its window regains focus, and every `FastDialog` hides them in its own window too. A swipe from the edge still shows them briefly (transient). Android TV is unchanged.
- **Compact top bar.** The seven top-bar buttons were each sized to their label (up to 100 dp) with 16 dp gaps, so with long localized labels the row stretched across almost the whole screen and could run into the title. They now have a fixed 76 dp width (48 dp circle, labels truncated with an ellipsis), 4 dp gaps, and sit together in one translucent rounded toolbar under the clock, about 575 dp wide. The title takes the remaining width and is truncated instead of overlapping.
- README / RELEASE: removed the obsolete flavor tables and task names; commands now match the single edition.
- Dependencies re-checked against Maven metadata: every library, AGP 9.5.1, Kotlin 2.4.21 and Gradle 9.9.1 are already the newest stable releases.

### Audio (Stage 4)
- **Preferred audio language selector**: user-picked audio track language (UI in `ProfessionalPlaybackSettings`, Auto = system default) persisted per preference and applied live through `DefaultTrackSelector` `preferredAudioLanguages`, ahead of system/fallback locales. Localized in all 8 app languages; stored alongside the audio offload toggle in `PreferencesManager`.

### Picture (Stage 5)
- **Interactive D-pad pan while zoomed**: when `videoZoom > 1f`, the directional remote pad adjusts `videoPanX`/`videoPanY` in small steps (clamped to [-1, 1]) instead of seeking; pan persists through the existing per-title `TitlePictureSettings` (SHA-256 keyed) path so each title remembers its framing.

### Audio delay / lip-sync
- **Now applied to playback**: `audio_delay_ms` (-5000…+5000 ms, 50 ms step) is no longer a stored-only value. Media3 still has no public audio-offset API (androidx/media#2184), so `DelayedAudioSink` (a `ForwardingAudioSink`) shifts the audio-driven master clock by the configured offset. Both flavor `CodecRenderersFactory` implementations (platform, NextLib and the FFmpeg-only recovery factory) route their sink through it, so hardware, software, passthrough and offload paths behave the same.
- **Live changes**: the settings stepper sends the new `SET_AUDIO_DELAY` session command; the service persists the value and the active sink picks it up on its next clock query, with no player rebuild.
- Not yet verified on a physical TV/AVR: only unit tests (`AudioDelayTest`) and compilation cover it.

### Release build fixed and verified on a real TV (15 November 2026)
- **Release build crashed at launch.** ML Kit instantiates its component registrars by reflection; the library consumer rules keep the class names, but R8 full mode dropped the no-argument constructors, so no ML Kit component registered and the first client (language identification) threw a `NullPointerException` while `PlayerViewModel` was being built. Added a `-keep ... ComponentRegistrar { <init>(); }` rule. This never showed in debug builds, which are not minified.
- **Gemini Nano removed.** It needs Android AICore, which televisions do not ship, and its client threw during construction. `LocalGenAiTranslatorFactory` returns the unavailable stub, the `genai-prompt` dependency is gone and `GEMINI_NANO_INCLUDED` is `false`.
- **minSdk is 24 again** (26 was only required by Gemini Nano). Three API-26 calls are now guarded: Picture-in-Picture params (two sites) and `canRequestPackageInstalls`.
- Verified on a TCL Android 14 TV (`armeabi-v7a`, 2.4 GB RAM): cold start about 1.4 s, HLS playback, Romanian UI text, no crash in the log.

### Playback error screen (15 November 2026)
- **Error text was hidden behind the centre play button**; it now sits below it.
- **Errors are explained in the app language** (no permission, not found, network, unsupported format, decoder) with the Media3 code name kept after the sentence. Mapping in `PlaybackErrors.kt`, covered by `PlaybackErrorsTest`.
- **A failed or still-loading item was labelled LIVE**: the bottom bar showed the live badge whenever the duration was unknown. It is now shown only for genuinely live streams.

### Verified on the TV (15 November 2026)
- **Audio delay**: measured through the media session clock. +1000 ms moved the clock by +992 ms and -500 ms by -508 ms against the zero baseline, live, with playback continuing.
- **Subtitle translation**: ML Kit on-device English to Romanian on an embedded subtitle track.
- **LAN cleartext playback**: an MP4 served over HTTP from a PC on the same network.
- **Local files**: media browser listing, playback, and an external `.srt` chosen through the system file picker.

### Local playback fixes (15 November 2026)
- **Media session title was the MediaStore id** (`1000000120`) for files opened from the media browser; it is now the movie name extracted from the file.
- **Tracks dialog stayed open after loading a subtitle file**; it now closes once the file is loaded.

### Subtitle sync rebuilt (15 November 2026)
- **Calibration had the wrong sign and ignored the current offset.** It stored `subtitle - audio` as the new offset; a subtitle that appears late must be pulled earlier, so the lag is now subtracted from the offset in effect (`suggestSubtitleSyncOffset`).
- **Calibration ran behind the settings dialog**, where position polling is paused and the video is covered: both marks read the same frozen position and always produced 0. Sync now lives in an on-screen panel (`SubtitleSyncPanel`) over the playing video, and marks read the player position directly.
- **Marks work in either order**, so subtitles that are early can be calibrated too, and a mark can be re-pressed before the second one is set.
- **Usable from a remote:** steps of 50 ms and 0.5 s plus reset (was 10 ms per press). The panel stays open instead of auto-hiding after 3.5 s, Back closes it, and it has its own **Sync** button in the top bar.
- **Offsets are per title only.** Every change used to overwrite a global default too, so a new film inherited the previous film's offset.
- **Offset display fixed:** values above one second were garbled (`-314 1 ms`); now `-3.141 s`.
- Labels that were hard-coded in English (`Mark A`, `Mark B`, `Apply`) are localized in all 8 languages.
- Tests: `SubtitleSyncTest` (9 cases). Checked on the TV: open, step, calibrate, apply, close.

### Single all-inclusive edition (14 November 2026)
- **Product flavors removed.** The three dimensions (`play`/`direct`, `platform`/`full`, `api24`/`genai`) and their 8 variants are gone. The app is always what `directFullGenai` was: direct updates, LAN-only cleartext playback, NextLib/FFmpeg software decoders and Gemini Nano. `minSdk` is now 26. Tasks are plain `assembleDebug` / `assembleRelease`.
- Flavor source sets were merged into `app/src/main`; the removed `platform`/`api24` stubs and the pre-merge build files are kept in `audit-evidence/removed-flavors-2026-11-14/`.
- **Release ships ARM ABIs only** (`arm64-v8a`, `armeabi-v7a`): about 57 MB instead of 196 MB for the debug APK. Debug keeps all ABIs for emulators.
- **Dependencies checked against Maven metadata**: only two were behind. NextLib 1.11.1-0.17.0 → 1.11.1-0.18.0 (pinned source commit `d774e7e6d65204d409dc679ab173c67030d4a7d4` updated in the packager, source offer, bundled notice and CI) and Gradle 9.9.0 → 9.9.1. Everything else was already the newest stable release.
- CI workflows, `verify-project-policy.py`, the `.bat` helpers and the baseline-profile module were updated for the single edition.
- Validated: `testDebugUnitTest` (135 tests, 0 failures), `lintDebug` and `lintRelease` (0 errors), `assembleDebug`, `assembleRelease` (R8 + baseline profile, signed with a throwaway verification key).

### Audit fixes (14 November 2026)
- **Romanian UI text was corrupted**: 139 lines of `values-ro/strings.xml` (and 4 ellipsis strings in `values/strings.xml`) were double-encoded UTF-8 ("SetÄƒri" instead of "Setări"); both files also carried a BOM. Repaired losslessly; originals kept in `audit-evidence/mojibake-backup-2026-11-14/`.
- **Auto-sync buttons showed raw keys**: eight `auto_sync_*` keys were used by the UI but missing from the `OxigenStrings` lookup table. Added, and `OxigenStringsTest` now scans the sources so a missing key fails the build.
- **Lint was failing (11 `MissingTranslation` errors)**, which blocks release builds (`abortOnError`): the eleven auto-sync / subtitle-sync strings are now translated in de, es, fr, it, pt and ru.
- **Settings dialog showed stale values**: `PlayerDialogState` was not recomputed when audio delay, audio offload, preferred audio language or the auto-sync state changed, so the audio-delay stepper kept adding to an old number and the auto-sync wizard never advanced.
- **Saved playback settings were not restored on a new player**: playback speed, skip-silence and match-frame-rate were only pushed when toggled. They are now applied in `ExoPlayerHelper` for every new player, including the software-decoder rebuild.
- **Audio focus**: the player now declares `USAGE_MEDIA` / `AUDIO_CONTENT_TYPE_MOVIE` and lets Media3 handle audio focus, so it pauses for calls/assistants and ducks for notifications.

## 2.2.8 - 8 November 2026

versionCode 19 / versionName 2.2.8. Staged "ultra advanced" playback upgrade built on the 2.2.7
toolchain. Validated with `assembleDirectPlatformGenaiDebug` + `testDirectPlatformGenaiDebugUnitTest`:
BUILD SUCCESSFUL, 128 tests, 0 failures, 0 errors. `update.json` stays `enabled: false` until a real
signed APK is published.

### Picture (Stage 1-2)
- **4K original-size rendering fix**: Media3 Compose `PlayerSurface` stretches the decoded frame
  instead of aspect-fitting it, so `videoResizeScale()` now undoes the stretch and restores the
  source proportions. FIT/FILL/STRETCH all compensate for the container geometry.
- **Diagnostics geometry line**: DAR / PAR (anamorphic detection) / active resize mode.
- **HDR label**: Dolby Vision / HLG / HDR10 / SDR detection from `colorInfo.colorTransfer` and the
  `dvh1`/`dvhe` codec prefixes (Media3 1.11.1 has no `hdrType`).
- **Manual aspect-ratio override**: Original / 16:9 / 4:3 / 2.35:1 / 1.85:1, persisted, localized in
  all 8 languages.
- **Zoom (1x-2x) and pan**: center-crop zoom with horizontal/vertical pan offsets, applied in the
  player `graphicsLayer`.
- **Per-title picture memory**: resize mode, aspect override, zoom and pan are remembered per media
  URI, falling back to the global defaults.

### Audio (Stage 3)
- **Audio offload / passthrough toggle**: the previously hard-coded `AudioOffloadPreferences` is now a
  user setting (default on, preserving prior behaviour), applied live through
  `trackSelectionParameters`. Audio track selection, preferred audio language, frame-rate matching
  (`videoChangeFrameRateStrategy`) and subtitle styling already existed and are unchanged.
- **Diagnostics**: the audio line now also shows the track language.


## 2.2.7 — 3 November 2026

versionCode 18 / versionName 2.2.7. Full toolchain and dependency refresh to the newest stable
releases. Every candidate version was read from the publishers' own Maven metadata (`maven-metadata.xml`
on Google Maven / Maven Central, plus `services.gradle.org/versions/current`) instead of being guessed,
and the result was validated with a real `assembleDirectPlatformGenaiDebug` +
`testDirectPlatformGenaiDebugUnitTest` run: BUILD SUCCESSFUL, 124 tests, 0 failures, 0 errors,
0 skipped. `update.json` stays `enabled: false` until a real signed APK is published.

### Toolchain
- **Gradle 9.7.1 → 9.8.2** (current stable, released 19 October 2026).
- **AGP 9.4.1 → 9.5.0** (newest stable, October 2026). These two move together on purpose: AGP
  9.5.0-alpha09 fixed *"Gradle 9.8.0 reports `Configuration.setVisible()` deprecations with AGP
  9.4.1"*, so upgrading Gradle alone would have introduced deprecation noise that AGP 9.5 removes.
- **Kotlin 2.4.20 → 2.4.21**, with the Compose compiler plugin on the same version (the two share one
  version number). Kotlin 2.5.0 exists but only as `2.5.0-Beta2`, so it is deliberately skipped.
  `buildEnvironment` shows AGP 9.5 declares only a Kotlin *floor* (`kotlin-gradle-plugin-api:2.2.10`,
  resolved upward to whatever the Compose compiler plugin requires) rather than pinning the line, so
  2.4.21 is genuinely the compiler that builds the app. The now-stale comment claiming
  "AGP 9.4 uses built-in Kotlin 2.4.20 by default" was corrected in `libs.versions.toml` to describe
  this floor behaviour.

### Dependencies
- `nextlib-media3ext` **1.10.1-0.13.0 → 1.11.1-0.17.0**. This one was a real mismatch, not merely a
  bump: the version prefix is the Media3 release the extension is compiled against, and the project
  was pairing a Media3 **1.10.1** extension with Media3 **1.11.1**. It now matches the Media3 line.
- `core-ktx` 1.19.0 → 1.19.1, `tracing-ktx` 2.0.2 → 2.0.5, `okhttp` 5.5.0 → 5.5.1, and ML Kit
  `genai-prompt` 1.0.0-beta4 → 1.0.0-beta5 (that library still has no stable release).

### GPLv3 corresponding-source sync (forced by the NextLib bump)
The `full` flavor ships NextLib under GPLv3 and pins the exact upstream revision, so bumping the
artifact without updating the source offer would have published a notice pointing at the wrong
commit. Every place that records the pin was moved to tag `v1.11.1-0.17.0` / commit
`a4c1e634278b7c918c3801e08c9d0e3eaee4f7ae` (resolved from the GitHub tag API, not guessed):

- `.github/workflows/android.yml` — **the most important one**. The release job `curl`s the pinned
  upstream archive and feeds it straight to the packager, so left alone CI would have published the
  *old* NextLib source alongside the new binary. Its `--version` also moved 2.2.4 → 2.2.7.
- `app/src/full/res/raw/nextlib_notice.txt` — the notice that actually ships inside `full` APKs,
  verified by reading `res/raw/nextlib_notice.txt` back out of the built APK. Its wording was made
  version-agnostic ("OxigenPlayer Pro full variants") so it cannot go stale again on the next release.
- `scripts/package-gpl-source.py` (`NEXTLIB_COORDINATE` / `NEXTLIB_TAG` / `NEXTLIB_COMMIT`).
- `SOURCE_OFFER_FULL.md` and `RELEASE.md`, which both carried stale `--version 2.2.4` examples
  (and `SOURCE_OFFER_FULL.md` a stale "OxigenPlayer Pro 2.2.4" heading).
- `CODECS_AND_AI.md`, which names the NextLib version the `full` flavor adds.
- The root `res/raw/nextlib_notice.txt` decompilation dump. This one matters: `package-gpl-source.py`
  excludes machine-local dumps only at depth 1 (`len(relative.parts) == 1`), so the root `res/` tree
  is swept into the corresponding-source ZIP and would otherwise still have carried the old pin.

Two existing guards caught parts of this and were updated rather than worked around.
`scripts/tests/test_package_gpl_source.py` asserts `nextlibCommit` and failed loudly on the old
value — exactly the guard rail it exists for. `scripts/verify-project-policy.py` hardcoded the old
hash; it now *derives* the expected commit from `NEXTLIB_COMMIT` in the packager and requires it in
both `SOURCE_OFFER_FULL.md` and the shipped notice, so a future bump cannot silently leave the source
offer behind. All 3 Python packaging tests pass, and the policy script now reaches its final step.

### Already newest stable — intentionally unchanged
Compose BOM 2026.09.00, Media3 1.11.1, Coil 3.6.3, `kotlinx-coroutines-play-services` 1.11.0,
`tv-material` 1.1.0, `activity-compose` 1.13.0, `lifecycle` 2.11.0, benchmark/baseline-profile 1.5.0,
`uiautomator` 2.4.0, `androidx.test:runner` 1.7.0, `androidx.test.ext:junit` 1.3.0, `espresso-core`
3.7.0, `startup-runtime` 1.2.0, `profileinstaller` 1.4.1, ML Kit translate 17.0.3 and language-id
17.0.6, JUnit 4.13.2. Several of these have a newer *pre-release* (Media3 1.12.0-alpha02,
activity-compose 1.14.0-alpha03, lifecycle 2.12.0-alpha05, espresso 3.8.0-alpha03,
benchmark 1.6.0-alpha01) that was rejected to keep every line on stable releases, matching the
project's existing policy of never shipping an experimental AGP/library.

### Not changed
- `compileSdk` / `targetSdk` stay at 37: API 37 is still the highest published platform (the SDK
  repository has no android-38), and it is also the newest platform installed locally.
- CI GitHub Actions keep their existing SHA pins, and no `gradle/verification-metadata.xml` is checked
  in, so no dependency-verification checksums needed regenerating. The `settings-gradle.lockfile` is
  only the version-catalog lock and is regenerated automatically.

### Changed
- versionCode 17 → 18; versionName 2.2.6 → 2.2.7 in `app/build.gradle.kts` and `update.json`.

## 2.2.6 — 31 October 2026

versionCode 17 / versionName 2.2.6. Composition-cost performance pass on the two hottest runtime
paths: localized-text resolution and the playback timeline labels. `update.json` stays
`enabled: false` until a real signed APK is published.

### Performance
- **Localized text is now memoised instead of re-resolved on every recomposition.** `OxigenStrings.get`
  is called from 232 sites, almost all of them inside Compose composition (`SettingsDialogs` alone has
  56, `SubtitleSearchDialog` 25, `DeviceMediaPickerDialog` 20). Every call ran a `Set.contains` check,
  a `ConcurrentHashMap.getOrPut` (which allocates the default-value lambda and does two map operations)
  and then `Resources.getString` — a native AssetManager lookup that allocates a brand-new `String`.
  Opening Settings or moving D-pad focus through a dialog therefore re-resolved and re-allocated dozens
  of identical strings per frame. Resolution is now memoised per `(language tag, resource id)` in a new
  `LocalizedStringCache`, so the steady-state cost is two allocation-free hash lookups and the returned
  reference is stable, which also lets Compose skip unchanged `Text` nodes by identity.
- **The timeline labels no longer recompose at the polling rate.** `TimeText`, `RemainingTimeText` and
  `TimelineStatusText` read `positionState.value` / `bufferedState.value` directly during composition,
  which subscribed their recompose scope to the ticker. They therefore recomposed — re-running text
  layout — 4x/second for elapsed/remaining time and 2x/second for the buffered label (500 ms/1 s on
  low-RAM devices), even though the elapsed/remaining labels can only change once per second and the
  buffered label only when a whole percent boundary is crossed. Each now derives the *displayed* value
  through `remember(...) { derivedStateOf { ... } }`, so the scope is invalidated only when the value
  on screen actually changes. The ticker itself is unchanged; only the recompositions it triggers are.

### Added
- `LocalizedStringCache.kt` — the two-level memoisation, extracted from `OxigenStrings` so its
  invariants are verifiable on the JVM without an Android `Context`.
- `LocalizedStringCacheTest` — six unit tests covering miss/hit behaviour, that languages never leak
  into each other (the failure mode if the tag were dropped from the key), that resource ids never leak
  across keys, that concurrent resolution of the same pair converges on one instance, and that `clear()`
  drops every language.

### Correctness notes
- The cache has exactly the lifetime of the already-existing `localizedContexts` map: both are dropped
  together in `OxigenStrings.initialize`, so a cached value can never be staler than the `Context` it was
  resolved from. Runtime language switching stays correct because the language tag is part of the key
  and the switch recreates the Activity.
- A missing `Context` deliberately does **not** cache the verbatim-key fallback, so calls made before
  `OxigenApp.onCreate` still resolve to real translations afterwards.
- Format strings (`media_file_count`, `track_number`, `external_storage`, `playback_speed`, …) are
  unaffected: `getString(id)` without format arguments returns the raw template, and callers apply their
  own formatting to it exactly as before.

### Changed
- versionCode 16 → 17; versionName 2.2.5 → 2.2.6 in `app/build.gradle.kts` and `update.json`.

## 2.2.5 — 24 October 2026

versionCode 16 / versionName 2.2.5. Bump de versiune post-audit. `update.json` rămâne `enabled: false` până la publicarea unui APK semnat.

### Bump
- versionCode 15 → 16; versionName 2.2.4 → 2.2.5 în `app/build.gradle.kts` și `update.json`.

## 2.2.4 — 19 September 2026

versionCode 15 / versionName 2.2.4. Toolchain pinned: AGP 9.4.1, Gradle 9.7.1,
Kotlin 2.4.20, Compose BOM 2026.09.00, Media3 1.11.1. The `update.json` updater
placeholder stays `enabled: false` until a real signed APK is published.

The entries below supersede the earlier partial `## 2.2.4 — Android TV repair pass`
section, whose content is merged into the Repaired/Verified subsections here.

### Repaired
- **All eight languages shipped double-encoded text.** Every non-ASCII character in `values-*/strings.xml` had been UTF-8 encoded, decoded as Windows-1252, and re-encoded as UTF-8, so the app rendered `Ãœbersetzen`, `SUBTÃTULOS`, `ÐÐ°Ð·Ð²Ð°Ð½Ð¸Ðµ` and similar garbage instead of `Übersetzen`, `SUBTÍTULOS`, `Название`. The raw bytes proved it: `c3 83 c2 8d` where correct UTF-8 for `Í` is `c3 8d`. 754 strings across the eight locales were repaired by reversing the transform byte-exactly (mapping each character back through cp1252/latin-1 and re-decoding as UTF-8); a dry run confirmed every affected line round-tripped to valid text, and lines that were already correct were left byte-identical. The English `values/strings.xml` was affected too, in four strings that carried `…` as `â€¦`.
- A guard was added to the policy gate so this cannot silently return (see Added).
- The software-first recovery path was configuring itself as if it still wanted hardware decoders. `ExoPlayerHelper` applied `EXTENSION_RENDERER_MODE_ON` to both factories, so the recovery build advertised "platform codecs first" while its entire purpose is to drop them. `FFmpegOnlyRenderersFactory` ignores that flag (it adds no platform renderers), so this was inert rather than harmful, but it invited exactly the wrong reading of the code. The two factories are now configured separately: `EXTENSION_RENDERER_MODE_OFF` for the software-only recovery build, `ON` for the normal hardware-first build.
- Playback no longer dies on hardware decoders that a device advertises through `MediaCodecList` but cannot actually run — the common case on car head units and budget TV boxes, where E-AC3 is rejected at `MediaCodec.configure()` or HEVC Main 10 dies on the first 10-bit frame. `PlaybackService` now classifies the failure and rebuilds the player once on the FFmpeg software decoders, carrying over the media item, position and play state and republishing the `MediaSession` so `MediaController` clients rebind transparently.
- `setEnableDecoderFallback(true)` was never enough on its own: Media3 picks a renderer once, at track selection, so its internal recovery only re-selects the same track against the same decoder. Reaching the packaged software decoders requires a new player instance, which is what the recovery path now does.
- Each media item gets its own retry, bounded to one attempt (`MAX_SOFTWARE_FALLBACK_ATTEMPTS`), so genuinely undecodable content surfaces its error instead of looping.

### Added
- `SoftwareFallbackPolicy.kt` centralises the failure classification. `ERROR_CODE_DECODING_RESOURCES_RECLAIMED` is excluded because Media3 already recovers from it internally, and the whole 4000-4099 decoder band is treated as recoverable because builds disagree on names for these codes (`ERROR_CODE_DECODER_INIT_UNSUPPORTED_SUBTYPE` vs `ERROR_CODE_DECODER_INIT_FAILED`); the symbolic name is used as a fallback so recovery is not tied to numbering.
- `CodecRenderersFactory.createSoftwareFirst()` per flavor: the `full` flavor drops the platform decoders for `FFmpegOnlyRenderersFactory`, while the `platform` flavor ships no third-party decoder and degrades to the platform ones. `softwareDecodersAvailable` is exposed as a shared property so `PlaybackService` compiles against both flavors without a `BuildConfig` branch.
- `SoftwareFallbackPolicyTest` covers the classification, including the excluded resources-reclaimed code, transient source errors and container parsing errors that must never trigger a rebuild.
- `scripts/verify-project-policy.py` now rejects double-encoded resource text. It flags any C1 control character (U+0080-U+009F) or a `Ã`/`Â`/`â` immediately followed by one, in `values*/strings.xml` and in the XML the app ships. These characters cannot occur in legitimate translated copy, so a match means the file was round-tripped through the wrong codec again.

### Changed
- Only one local debug artifact is produced now. `BUILD_DEBUG.bat` builds the single `directFullClassicDebug` variant, deletes stale `apk-debug\app-*-debug.apk` copies and writes `apk-debug/OxigenPlayer-2.2.4-debug.apk` (163.8 MB; verified with `aapt2 dump badging`: versionCode 15, versionName 2.2.4, minSdk 24, targetSdk 37, leanback launcher activity, four ABIs). `BUILD_ANDROID_TV.bat` keeps its unit-test + lint + assemble verification but runs it against the same variant instead of `directPlatformClassicDebug`, so the project no longer offers two competing "debug models".
- Both scripts now invoke the wrapper as `call "%~dp0gradlew.bat"`. `cmd` on this machine does not search the current directory (`NoDefaultCurrentDirectoryInExePath=1`), so the previous bare `call gradlew.bat` failed with "'gradlew.bat' is not recognized as an internal or external command" even when run from the project root; `BUILD_DEBUG.bat` was validated end to end after the fix (BUILD SUCCESSFUL, APK copied).
- `VARIANTE_BUILD.md` documents every flavor option in Romanian: what `play`/`direct`, `platform`/`full` and `classic`/`nano` change, why `nano` brings nothing on a television (it needs Android 8.0+ plus a device that actually ships AICore with Gemini Nano, and fails closed instead of falling back to the cloud), and why `directFullClassicDebug` is the single debug build - `direct` allows sideloading and LAN playback, `full` carries the FFmpeg software-decoder recovery path repaired above, `classic` keeps the API 24+ translation stack. `README.md` points to it.
- The eight-flavor matrix itself is deliberately unchanged: `scripts/verify-project-policy.py` -> `verify_variant_matrix()` fails if CI stops covering debug and release for all eight combinations. Only the local deliverable was simplified; the seven non-selected debug APKs were removed from `apk-debug\` and from `app/build/outputs/apk/*/debug/` (about 2.1 GB reclaimed), and each one regenerates with a single `assemble<Variant>Debug` task.

### Improved
- **Torrent-server playback rebuffers less on large files.** Starting and resuming playback now waits for 2.5 s / 5 s of buffered media instead of 1-1.5 s / 2-3 s, so a bursty TorrServer stream no longer restarts on a 2 s cushion that depletes immediately and loops the buffering spinner. Constant-bitrate seeking is enabled so ±10 s remote jumps in large containers without a seek index (MPEG-TS and friends served before the index arrives) estimate the byte offset instead of forward-scanning gigabytes. The transient source-error recovery budget drops from 5 to 2 attempts and the HTTP load-retry count from 6 to 3: each re-prepare forces the extractor to rescan from the resume position, so extra attempts only multiply long spinners on a dead swarm instead of bridging it.

### Changed
- **The confusing `classic` / `nano` flavor names are gone.** The two options of the `ai` dimension only differ in the subtitle-translation engine, and neither old name said so. They are renamed to `api24` (standard ML Kit + provider stack, API 24+) and `genai` (adds the on-device Gemini Nano engine, API 26+): each name now states what the variant contains. This is purely a rename — no behavior, dependency or manifest change (`app/src/classic` -> `app/src/api24`, `app/src/nano` -> `app/src/genai`, `nanoImplementation` -> `genaiImplementation`, `missingDimensionStrategy("ai", "api24")` in `baselineprofile`). The single local debug build is now `directFullApi24Debug` (`BUILD_DEBUG.bat` / `BUILD_ANDROID_TV.bat` updated, delivered APK keeps the name `apk-debug\OxigenPlayer-2.2.4-debug.apk`). CI matrix, `security.yml`, `verify-project-policy.py` and the docs (`README.md`, `VARIANTE_BUILD.md`) were renamed in lockstep, so the eight-combination CI coverage gate still passes with the new names. Earlier changelog/audit entries keep the old names because they are dated records of builds made at that time.

### Verified
- **All 8 release variants** now build, minify, shrink and sign end to end (previously none had ever been built): `direct`/`play` × `full`/`platform` × `classic`/`nano`. Each APK was checked with `aapt2 dump badging` (versionCode=15, versionName=2.2.4, targetSdk=37; minSdk 24 on classic, 26 on nano) and `apksigner verify` (v2 + v3 signatures). `REQUEST_INSTALL_PACKAGES` is present only on the 4 `direct` variants, as intended.
- `lintDirectFullClassicRelease` passes with `abortOnError=true`.
- These APKs are signed with a disposable local verification key, exactly as CI generates one. They confirm the release pipeline is healthy but are **not distributable** — a real release still needs the production `OXIGEN_RELEASE_*` keystore.
- Unit tests: 118 tests, 0 failures, 0 errors, 0 skipped.
- **Instrumented tests now run and pass** on an Android TV emulator (7 tests, 0 failures), replacing the earlier "no device reachable" status. The `connectedAndroidTest` Gradle task is unusable offline because `com.android.tools.utp:gradle-work-action` is not cached, so the app and test APKs are installed with `adb install -r -t` and the suite executed with `am instrument -w`. Recorded in `audit-evidence/instrumented_tests.log`.
- Reviewed the NextLib `1.10.1-0.13.0` sources against media3 `1.11.1`. `FFmpegOnlyRenderersFactory` only overrides the video and audio renderers, so text and metadata renderers still come from `DefaultRenderersFactory` and are unaffected by the software-first switch; `NextTextRenderer`'s subtitle-offset behaviour is not used by this app (captions are rendered by `SubtitleEngine` through its own subtitle view), so losing it in the recovery build is not a regression. The cross-version combination is still unexercised on a device.


### Repaired (Android TV)
- Android TV fixes per `ANDROID_TV_AUDIT_2_2_4.md`: separate `oxigenplayer://` intent
  filter without MIME, single back-press exit path, permission-aware MediaStore cache,
  lazy-list focus request, A-B loop ticker kept alive, per-profile video buffer targets.
- The versionCode 15 / versionName 2.2.4 bump was missing from `app/build.gradle.kts`,
  which still declared 14 / 2.2.3 and produced an APK stamped 2.2.3.

### Verified (earlier pass)
- `directFullApi24Debug` rebuilt offline: BUILD SUCCESSFUL, APK reports versionCode 15 /
  versionName 2.2.4, targetSdk 37, minSdk 24, leanback launcher activity, four ABIs.
- Unit tests: 118 tests, 0 failures, 0 errors, 0 skipped. Lint `abortOnError=true`: 0 errors.
- Instrumented tests pass on an Android TV emulator (7 tests, 0 failures), installed with
  `adb install -r -t` and executed with `am instrument -w` because
  `com.android.tools.utp:gradle-work-action` is not cached offline. Recorded in
  `audit-evidence/instrumented_tests.log`.
- `scripts/verify-project-policy.py` exits 0 (a machine-local `local.properties` had been
  making it fail).
- The release APKs were signed with a disposable local verification key, exactly as CI
  generates one. They confirm the release pipeline is healthy but are **not distributable** —
  a real release still needs the production `OXIGEN_RELEASE_*` keystore.


## 2.2.3 — Translation reliability

- Removed the unsupported direct Google Translate web endpoint from the app and migrate old `GOOGLE`/`GOOGLE_TRANSLATE` preferences back to `AUTO`.
- Added **Mozhi** as a free configurable translation provider with a current public default instance and support for alternate/self-hosted instances.
- Mozhi can route through `google`, `duckduckgo`, `mymemory`, `libretranslate`, `reverso`, `yandex` or `deepl`; the engine is configurable in Translation settings.
- Kept ML Kit as the preferred offline path, Apertium and MyMemory as keyless public fallbacks, LibreTranslate/Ollama as free self-hosted options, and DeepL only when a valid key is configured.
- AUTO no longer depends on an undocumented Google web API and now falls back through supported/configurable providers.

# Changelog

## 2.2.2 — Android TV fast-card performance pass, 19 September 2026

### Performance
- Added Android TV MediaStore prewarming on a background IO coroutine after the critical cold-start path, so the Media card normally opens from an in-memory snapshot.
- Added a single-flight MediaStore loader: if the user opens the Media card while prewarming is still running, the dialog joins the same scan instead of launching a duplicate database query.
- Indexed media files by storage volume once per snapshot; switching Device/USB storage chips is now O(1) instead of filtering the complete library on every selection.
- Replaced Material3 `ListItem` media rows with a shallow fixed-height TV row to reduce composition and measurement cost while scrolling large libraries.
- Cached localized media-row labels and the ML Kit available-language list so opening unrelated TV cards does not rebuild those collections.
- Removed `IntrinsicSize.Min` from the six top-bar control cards, eliminating an extra intrinsic-measurement path whenever controls appear.
- Disabled full-screen pointer gesture recognizers and dialog backdrop gesture detectors on television devices; D-pad/remote navigation keeps only the input machinery it needs.
- Marked local-media snapshot models `@Immutable` to improve Compose skipping for unchanged media/card data.
- Extended the shipped baseline profile with the TV focus, dialog, player-control and local-media hot-path classes.

### Changed
- Bumped application metadata to versionCode 13 / versionName 2.2.2.

## 2.2.1 — stable toolchain and dependency refresh, 19 September 2026

### Changed
- Replaced experimental Android Gradle Plugin 9.5.0-alpha05 with stable AGP 9.4.0.
- Aligned the Compose compiler plugin to Kotlin 2.2.10, the stable built-in Kotlin line used by AGP 9.4.
- Updated Compose BOM to 2026.09.00, Media3 to 1.11.1, Coil to 3.6.3, Baseline Profile/Benchmark to 1.5.0, UiAutomator to 2.4.0 and Tracing to 2.0.2.
- Kept already-current components pinned: Gradle 9.7.1, Core KTX 1.19.0, Activity 1.13.0, Lifecycle 2.11.0, TV Material 1.1.0, OkHttp 5.5.0, coroutines-play-services 1.11.0 and the existing ML Kit/NextLib lines.
- Updated GitHub Actions setup-java to 6.0.1 and CodeQL Action to 4.38.1 using immutable commit SHA pins.
- Bumped application metadata to versionCode 12 / versionName 2.2.1.

### Repaired / cleaned
- Removed machine-local `local.properties` and extracted build/cache artifacts from the distributable source.
- Removed stale dependency-lock and verification metadata generated for the previous dependency graph. The existing `dependency-baseline` CI job regenerates reviewable lockfiles and SHA-256 verification metadata against the new graph.
- Kept the updater disabled until a real signed 2.2.1 binary, size and SHA-256 are published.

## Unreleased — Baseline Profile + build-configuration-cache pass, 29 August 2026

### Added
- New `:baselineprofile` generator module with `BaselineProfileRule` covering the
  cold-start path (launcher → player). Profiles ship only inside release artifacts and
  are produced on demand via `:app:generate<Variant>ReleaseBaselineProfile` on a
  connected API 33+ device/emulator or a rooted device — never through CI gates.
  `profileinstaller` (already present) applies the shipped profile at first launch, so
  ART AOT-compiles the startup path and the first playback frame at install time.
- `generatePlayPlatformClassicReleaseBaselineProfile` was executed locally; the emulator
  (software-rendered Pixel_10_Pro, no KVM) was too slow to complete ART profile iterations
  within the allowed time, but the shared handwritten `app/src/main/baseline-prof.txt` is
  already in place and packaged into every release artifact. Re-generation on a real device
  or a CI runner with hardware acceleration will succeed and produce a richer profile.

### Performance
- Runtime: `MainActivity` clock replaced the fixed 30 s `Calendar`/`String.format` poll
  with a `SimpleDateFormat` and wakeups aligned to the wall-clock minute — halves top-bar
  wakeups while keeping accuracy, removes per-tick allocations.
- CI instrumented gate is now scoped to `:app:...`, so the new `:baselineprofile` module
  never executes inside the connected-test matrix and cannot regress the `android.yml` pass.
- Configuration cache was evaluated but reverted: AGP 9.5 `validateSigning*` tasks are not
  yet config-cache-compatible when extended with `doFirst`. The build already completes in
  <8 minutes with parallel + caching; a config-cache pass is deferred to a future AGP update.

## Unreleased — focus-card performance pass, 28 August 2026

### Changed
- Migrated `Modifier.tvFocusable` from `composed {}` to three `Modifier.Node`
  implementations (focus/animation, scale+z-index, border) — the previous
  `composed`-based modifier blocked Compose's skipping optimization, so every
  TV card recomposed on each focus change. Focus state now drives the draw and
  graphics-layer phases directly: focus movement no longer recomposes card
  content at all.
- Replaced per-recomposition `animateFloatAsState`/`animateColorAsState` with a
  single frame-callback progress value shared by the scale and border nodes,
  cutting allocations during focus animations.

## Unreleased — post-release audit pass, 20 August 2026

Fixes only; no `versionCode`/`versionName` change.

### Fixed
- `VideoResizeModeTest.stretchHandlesNarrowPortraitContainers` had its axis
  assertions swapped and failed on every CI variant. The stretch math itself was
  correct: a wide video letterboxed in a narrow portrait container has a vertical
  gap, so only the Y scale grows. All 101 unit tests now pass.
- `PlaybackService` now uses the non-deprecated
  `AcceptedResultBuilder(session, controller)` from Media3 1.11, removing the last
  compiler warning from the debug build.

### New
- Registered a custom `oxigenplayer://` deep-link scheme in the main and Direct
  manifests and handled it in `MainActivity`, so Jellyfin, Stremio, Infuse and
  similar apps can launch their current stream in OxigenPlayer as an external
  player (`oxigenplayer://play?url=<encoded https/rtsp/rtsps|LAN http>...`). The
  carried media URL is validated with the existing remote-playback policy, so the
  deep link cannot bypass the same cleartext, host or scheme rules as a normal
  video intent. Covered by new instrumented deep-link tests.
- Added a free **Google Translate** translation engine (keyless web endpoint).
  It needs no API key, accepts the same ISO language codes as ML Kit and is
  selectable explicitly and included in the automatic translation chain as a
  final fallback, giving the app a second always-available cloud provider
  alongside Apertium and MyMemory (`TranslationManager` + `SettingsDialogs`).

### Performance

- Reopening the media library in the same process is now nearly instant: the last
  successful MediaStore snapshot is cached in memory and reused, so the cards appear
  immediately instead of re-scanning the database on every open. A fresh MediaStore
  scan still runs when the user selects **Refresh** or when media permissions change
  (`LocalMediaLibrary` + `MainActivity`).

### Hardened
- `scripts/package-gpl-source.py` additionally skips `.kotlin/`, `.artifacts/`,
  `__pycache__/` and `.pyc`/`.pyo` files so a working tree can never leak
  development artifacts into a corresponding-source archive (covered by a new
  packager unit test).
- `.gitignore` now covers local build/audit logs and Python bytecode caches.

### Removed / documentation
- Dropped eighteen stray build-log files and a `MainActivity.kt` dump from the
  repository root.
- Corrected version drift: `CODECS_AND_AI.md` now states Media3 1.11.0 and the
  `RELEASE.md`, `SOURCE_OFFER_FULL.md`, `README.md` and CI packaging examples use
  versionCode 11 / versionName 2.2.0.

## 2.2.0 — 2026-08-18

### Dependency updates

- Gradle wrapper 9.6.1 → 9.7.0 (with the official SHA-256 checksum).
- Jetpack Compose BOM 2026.06.00 → 2026.08.00.
- AndroidX Media3 1.10.1 → 1.11.0, which includes a timing-dependent ExoPlayer concurrency
  crash fix and lifecycle-aware `MediaSessionService`.
- OkHttp 5.4.0 → 5.5.0 and `androidx.tracing:tracing-ktx` 1.3.0 → 2.0.0.
- Android Gradle Plugin stays at 9.3.1 and Kotlin at 2.4.10, the current stable releases.
- `nextlib-media3ext` stays pinned at 1.10.1-0.13.0 (the latest upstream build); it is compiled
  against the Media3 1.10.x decoder-extension surface, which remains binary-compatible in 1.11.0.

### New playback features

- **Skip silence**: silent gaps in the audio track are skipped during playback. Toggle in
  Settings → Playback; applied at player creation and live per session.
- **Aspect-ratio modes**: Fit (letterbox), Fill (center-crop) and Stretch are applied to the
  video frame on the fly and the choice persists across relaunches. The scale math is unit-tested.
- **A-B loop repeat**: set the loop start, then the end point from the bottom bar chip; the
  timeline rewinds to A whenever playback passes B. Reset automatically on media change.
- **Open network stream**: a new top-bar button plays an HLS/DASH/RTSP(RTSPS) or direct
  HTTPS/LAN media URL. Input is validated with the same `PlaybackUriPolicy` used for session
  intents, so Play editions still refuse public HTTP while Direct/LAN keeps its LAN exception.

### Security

- Reverted the repository default `OXIGEN_EMBED_PROVIDER_CREDENTIALS` to `false` and removed
  the committed `oxigenplayer-secrets.properties` that shipped public-client API keys in the
  previous repair artifact. Those keys are now considered exposed; rotate them before reuse.
  Developer-local keys can still be embedded in local debug builds via `~/.gradle` as
  documented in `secrets.properties.example` and `SECURITY.md`.

### Other

- Version bumped to 2.2.0 (versionCode 11); the `update.json` placeholder was regenerated
  (self-updates remain disabled until a signed artifact is published).
- Added 12 localization keys to all eight languages (en, ro, es, fr, de, it, pt, ru).
- New unit tests: `AbRepeatTest` and `VideoResizeModeTest`.

## Unreleased — audit and repair pass, 7 August 2026

Source-only corrections. `versionCode`/`versionName` are deliberately left at 10 / 2.1.2:
bumping them is a release decision that also requires regenerating `update.json` with a real
artifact hash and size. See `REPAIR_REPORT.md` for the full record.

### Fixed
- Declared and requested `POST_NOTIFICATIONS`, without which the `MediaSessionService`
  notification and its transport controls were suppressed on Android 13 and newer.
- `PlaybackService` resume checkpoints no longer depend on `MediaItem.localConfiguration`,
  which is stripped across the session boundary and made every controller-driven checkpoint
  a no-op.
- Keystore-backed credentials are initialized on demand, so provider API keys read or written
  during the first moments after launch are no longer lost.
- Corrected the low-RAM buffer tiering, which applied the 2 GB profile to every device
  reporting `isLowRamDevice`.
- Declared `android:enableOnBackInvokedCallback` rather than relying on the platform default.
- Clamped SRT export timestamps so a negative time cannot produce an invalid subtitle file.
- Gated the top-bar clock on the activity lifecycle, removed a no-op display-mode write, added
  the missing `super.onCleared()`, and dropped the orphaned `seek_backward` string from all
  eight locales.
- Picture-in-Picture now follows the decoded video aspect ratio, including anamorphic content,
  instead of forcing every window to 16:9.
- Voice search sends an IETF language tag; it previously passed a `Locale` object that the
  platform ignored, so the requested language never took effect.
- Removed a `remember` call from a conditional position in the subtitle-search dialog branch.

### Added
- The About dialog now conveys the GPL-3.0-only licence, the corresponding-source repository and,
  in the `full` flavor, the NextLib notice. Both licence resources shipped in the APK before but
  were referenced from nowhere.
- `scripts/verify-project-policy.py` cross-checks the supported-language list across
  `strings.xml`, `locales_config.xml`, Gradle `localeFilters` and
  `PreferencesManager.SUPPORTED_APP_LANGUAGES`, and verifies that every `OxigenStrings.get` key
  is mapped, which the compiler cannot check.

### Changed
- `OxigenStrings.supportedLanguages` delegates to `PreferencesManager.SUPPORTED_APP_LANGUAGES`
  rather than repeating the list.
- Replaced the tautological `OxigenStringsTest` assertion with checks that can actually fail, and
  added `PictureInPictureRatioTest`.

## 2.1.2 — 2026-08-07

### Low-RAM Android TV performance

- Added a process-wide device performance profile and Compose low-RAM mode.
- Removed focus scale, z-index promotion and focus color animations from D-pad cards on constrained TVs.
- Disabled non-essential player-control, timeline and subtitle motion animations on low-RAM hardware.
- Reduced player/UI polling while preserving direct 10-second Left/Right seek behavior.
- Suspended hidden playback-position polling while dialogs are open and removed underlying control layers.
- Added tighter ExoPlayer buffering for very-low-RAM devices while retaining adaptive bitrate selection.
- Reduced subtitle/translation cache sizes, prefetch depth and concurrency only on low-RAM hardware.
- Delayed SubtitleSearchService/OkHttp allocation until the subtitle-search screen is opened.
- Optimized MediaStore loading by appending directly into one result list and using allocation-free case-insensitive sorting.
- Added LazyColumn content types and cached row labels for faster media-card recycling and focus movement.
- Kept the richer visual profile unchanged on capable devices.

## 2.1.1 — 2026-08-07

### Android TV seek optimization

- Removed the center seek direction/time overlay.
- Removed the timeline seek-time popup bubble.
- Left/Right now seeks immediately on the first press when playback controls are hidden.
- Unified TV remote seek steps to a predictable 10 seconds.
- Reworked repeated-key seek debouncing to use one coroutine per seek burst, reducing allocations on low-memory TVs.
- Added overflow-safe seek calculations and unit tests for boundaries and repeated presses.

## 2.1 — 2026-08-06

### Publication blockers closed

- Added the root GPL-3.0-only `LICENSE`, SPDX headers and a pinned NextLib corresponding-source process.
- Added the exact NextLib tag and commit and required its complete source in every `full` source package.
- Fixed the real `Intent.ACTION_VIEW` path for `rtsps://` and added an instrumented regression test.

### Security and privacy

- Replaced deprecated `EncryptedSharedPreferences` usage with AES-GCM keys generated directly in Android Keystore.
- Limited Direct-edition HTTP to explicit LAN targets, checked every redirect and verified the connected route address.
- Added network interceptors for every public HTTPS redirect hop.
- Hardened numeric-host parsing, APK ZIP header validation and GPL source packaging against secret files.
- Added repository secret scanning, CodeQL and dependency review workflows.

### Android and Android TV

- Migrated all eight languages to native Android resources and added Android 13+ per-app language integration.
- Expanded configuration handling for TV/display/font/density changes.
- Stopped an idle playback service when the app task is removed.
- Moved secure-storage initialization off the activity startup thread and shared one application-scoped preferences manager.

### Build and release

- Set versionCode 8 / versionName 2.1 and explicit Kotlin JVM target 17.
- Moved connected tests to API 37 and added Direct/Full/Nano TV coverage.
- Added dependency locking plus a CI-generated dependency-verification baseline for review.
- Removed debug APKs from uploaded CI artifacts and made the GPL source packager testable and portable.

## 2.0 — 2026-08-05

### Security

- Enforced HTTPS and provider-domain boundaries for every subtitle redirect.
- Moved subtitle downloads to the public-only DNS client.
- Split Play (no cleartext) and Direct/LAN (explicit cleartext media) policies.
- Disabled debug credential embedding by default.
- Added encrypted bring-your-own-key configuration for subtitle providers and DeepL.
- Kept release BuildConfig provider secrets empty.

### Android TV

- Added a dedicated TV launcher banner.
- Added INFO diagnostics and MENU settings shortcuts.
- Added semantic role, selection and disabled state to reusable D-pad controls.
- Added phone and Android TV connected-test jobs.
- Removed forced process termination and retained standard Android lifecycle behavior.

### Playback

- Added Auto, 4K UHD, Full HD, HD and Data Saver quality profiles.
- Added seamless frame-rate matching, repeat-one and a monotonic sleep timer.
- Added a localized technical playback diagnostics overlay.
- Added command-availability checks for MediaController quality, speed and repeat operations.
- Added consistent RTSPS validation.

### Providers and localization

- Added user configuration for OpenSubtitles, Subs.ro, SubDL, TMDB and DeepL.
- Replaced raw exception messages in subtitle UI with localized user-safe errors.
- Expanded all eight language maps to 193 matching keys and verified format tokens.

### Build and release

- Expanded CI to all eight debug and all eight release flavor combinations.
- Added wrapper JAR checksum and repository policy validation.
- Added portable corresponding-source packaging and GPL release checklist.
- Aligned update metadata URL validation with runtime policy.
- Repackaged source paths for cross-platform ZIP compatibility.
