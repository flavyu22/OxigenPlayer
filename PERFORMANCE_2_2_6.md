# OxigenPlayer Pro 2.2.6 — composition-cost performance pass

**Date:** 2026-10-31
**Base:** versionCode 16 / versionName 2.2.5 → versionCode 17 / versionName 2.2.6

## Goal

Cut per-frame work on the two runtime paths that still re-did identical work on every recomposition:
localized-text resolution and the playback timeline labels. Both are pure overhead — they change
nothing the user can see — so they are the cheapest wins left after the 2.1.2 low-RAM pass and the
2.2.2 Android TV fast-card pass, which had already addressed buffers, caches, MediaStore scanning,
media-row layout and intrinsic measurement.

## Implemented changes

### 1. Localized text is memoised (`OxigenStrings` + new `LocalizedStringCache`)

`OxigenStrings.get(lang, key)` is the single text-resolution facade for the whole UI and is called
from **232 sites**, nearly all inside Compose composition:

| File | Call sites |
| --- | --- |
| `ui/SettingsDialogs.kt` | 56 |
| `ui/SubtitleSearchDialog.kt` | 25 |
| `ui/DeviceMediaPickerDialog.kt` | 20 |
| `ui/ProfessionalPlaybackSettings.kt` | 20 |
| `MainActivity.kt` | 20 |
| `ui/ProfessionalPlaybackDiagnostics.kt` | 17 |
| `PlayerComponents.kt` | 17 |
| `ui/UpdateDialog.kt` | 15 |
| `ui/ProviderAccountSettings.kt` | 13 |
| `ui/TrackSelectionDialog.kt` | 10 |
| others | 19 |

Each call previously performed:

1. `resourceIds[key]` map lookup;
2. `lang.takeIf { it in supportedLanguages }` — a `Set.contains`;
3. `localizedContexts.getOrPut(...)` — `getOrPut` allocates the default-value lambda and runs two map
   operations;
4. `localized.getString(resourceId)` — a **native AssetManager lookup that allocates a new `String`**.

Steps 3–4 ran on *every recomposition* of every visible label. Opening Settings, moving D-pad focus
through a dialog, or any state change in the player overlay therefore re-resolved and re-allocated
dozens of identical strings per frame — exactly the workload an entry-level Android TV cannot afford.

Resolution is now memoised per `(language tag, resource id)` in `LocalizedStringCache`. The
steady-state path is two allocation-free hash lookups and no JNI call. Because the returned reference
is now stable across recompositions, Compose can also skip unchanged `Text` nodes by identity rather
than comparing freshly allocated equal strings.

Memory is bounded by `supportedLanguages` × mapped keys — 8 languages × ~200 short strings.

**Why this cannot change behaviour:**

- The cache has exactly the lifetime of the pre-existing `localizedContexts` map: both are cleared
  together in `OxigenStrings.initialize`. A cached value can therefore never be staler than the
  `Context` it was resolved from.
- The language tag is part of the key, so runtime language switching (which recreates the Activity)
  can never surface text resolved for a different locale.
- A missing `Context` deliberately does **not** cache the verbatim-key fallback, so calls issued
  before `OxigenApp.onCreate` still resolve to real translations afterwards.
- Format strings (`media_file_count`, `track_number`, `external_storage`, `new_version_available`,
  `saved_to`, `save_error`, `playback_speed`, `minutes_format`) are unaffected: `getString(id)` with no
  format arguments returns the raw template, and callers apply their own formatting exactly as before.

### 2. Timeline labels derive the displayed value (`PlayerComponents`)

`rememberTimelineTicker` samples the player position at 250 ms (500 ms low-RAM, 50 ms while scrubbing)
and the buffered position at 500 ms (1 s low-RAM). The ticker is correctly gated on `isVisible`, which
already excludes dialogs, so it is not the problem. The problem was who read it:

| Composable | Read before | Recomposed | Text can change |
| --- | --- | --- | --- |
| `TimeText` | `positionState.value` in composition | 4×/s | 1×/s |
| `RemainingTimeText` | `positionState.value` in composition | 4×/s | 1×/s |
| `TimelineStatusText` | `bufferedState.value` in composition | 2×/s | on 1% boundary |

Reading the ticker during composition subscribed each label's recompose scope to it, so every sample
recomposed the label and re-ran text layout — even though the rendered text provably could not have
changed. `TimelineStatusText` was the worst case: it recomposed an `AnimatedContent` subtree twice per
second to show a whole percent that moves only when buffering crosses a 1% boundary.

Each label now derives the *displayed* value first:

```kotlin
val seconds by remember(positionState) { derivedStateOf { positionState.value / 1_000L } }
```

The derived state absorbs the 4 Hz sampling; the recompose scope is invalidated only when the derived
value changes. The `remember(seconds) { formatTime(...) }` text memoisation that already existed is
kept, so the string is still built only when the second changes.

The slider itself needed no change — it already reads `positionState`/`bufferedState`/`expansionState`
inside `onDrawBehind`, so it invalidates only the draw phase.

## Validation performed

- `:app:assembleDirectPlatformGenaiDebug` — **BUILD SUCCESSFUL**; the produced APK reports
  `versionCode='17' versionName='2.2.6'` under `aapt2 dump badging`.
- `:app:testDirectPlatformGenaiDebugUnitTest` — **124 tests, 0 failures, 0 errors, 0 skipped**.
- New `LocalizedStringCacheTest` (6 tests) verifies miss/hit behaviour, cross-language isolation,
  cross-key isolation, convergence under concurrent resolution, and `clear()`.
- `scripts/verify-project-policy.py` — every check relevant to this pass passed, including
  `verify_string_lookup_keys` (the `OxigenStrings` key-mapping gate the compiler cannot check),
  `verify_localization_shape`, `verify_language_sources_agree` and `verify_source_headers` (the SPDX
  header requirement for the two new `.kt` files). The script still exits non-zero at its **last**
  step, `scan-secrets.py`, because this working copy contains a machine-local `local.properties`
  pointing at the Android SDK. That is a pre-existing condition of the checkout — the file is
  gitignored and required for the build — and is unrelated to these changes.

## Recommended device validation

This build environment has no Android device, so the gains are reasoned from the code paths rather
than measured. To confirm on hardware, use a release build with R8 and the Baseline Profile installed
and capture Perfetto/Android Studio Profiler traces on at least one low-end 2–3 GB Android TV:

1. **Allocation rate with Settings open** while moving D-pad focus — should drop sharply, since the
   per-frame `String` allocations from `Resources.getString` are gone.
2. **Recomposition counts during playback** with the bottom bar visible (Layout Inspector →
   recomposition counts): `TimeText`, `RemainingTimeText` and `TimelineStatusText` should now tick
   ~1×/s, ~1×/s and only on percent changes respectively, instead of 4×/s, 4×/s and 2×/s.
3. **Time-to-first-frame for Settings / Subtitle Search / Media** on a cold open.
