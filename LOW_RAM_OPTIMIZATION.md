# OxigenPlayer Pro 2.1.2 — Android TV Low-RAM Optimization

**Date:** 2026-08-07

## Goal

Reduce RAM pressure, transient allocations, recompositions and GPU layers on Android TV devices with limited memory while preserving playback, subtitles, translation, dialogs and the direct 10-second Left/Right seek model.

## Implemented changes

1. **Single device profile** — `DevicePerformance` queries `ActivityManager` once per process. `LocalLowRamMode` propagates the result through Compose, so each card does not query Android services independently.
2. **TV focus cards** — low-RAM mode uses an immediate outline without scale, z-index promotion or animated border color.
3. **Player overlays** — non-essential top/center/bottom control motion is disabled on constrained devices. Timeline expansion and subtitle padding become instantaneous.
4. **Polling** — low-RAM timeline position uses 500 ms instead of 250 ms; buffered position uses 1 s instead of 500 ms. Invisible playback-position polling pauses while a dialog is open.
5. **Dialogs** — low-RAM dialogs use an opaque black scrim, player controls are removed behind dialogs, and text subtitles are not rendered behind the dialog on constrained devices.
6. **ExoPlayer buffers** — devices at or below 2 GB use a 5–15 s buffer window; ordinary low-RAM devices use 8–25 s; normal devices keep 15–50 s. Low-RAM back buffer remains disabled.
7. **Translation/subtitle caches** — translation cache 600 vs 2,000 entries; translator pool 3 vs 8; subtitle cache 1,200 vs 5,000; prefetch window 10 vs 25; queue 32 vs 96; translation concurrency 2 vs 4.
8. **Subtitle search** — `SubtitleSearchService` and derived OkHttp clients are allocated only when the subtitle-search dialog is opened.
9. **Media browser** — MediaStore results append into one list instead of creating one result list per volume/type. Sorting uses `String.CASE_INSENSITIVE_ORDER` rather than repeated `lowercase()` allocations. Lazy rows use stable content types and visible row labels are cached across focus recompositions.
10. **Seek preservation** — 2.1.1 behavior is retained: Left/Right respond immediately in 10-second increments and only one decoder seek is committed after a repeated-key burst.

## Compatibility

No user-facing capability is intentionally removed. The low-RAM profile changes presentation cost, cache sizes, prefetch/concurrency and buffer limits only. Higher-memory devices keep richer animations and larger caches.

## Validation performed

- `scripts/verify-project-policy.py`: PASS
- Python scripts compile: PASS
- all project XML files parse: PASS
- modified Kotlin source parser smoke-check: no syntax markers such as `expecting` or `unexpected tokens`
- Gradle wrapper executable bit restored
- full Gradle build: NOT AVAILABLE in this environment because `services.gradle.org` cannot be resolved to download Gradle 9.6.1
