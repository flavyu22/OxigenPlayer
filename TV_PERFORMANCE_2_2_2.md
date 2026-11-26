# OxigenPlayer Pro 2.2.2 — Android TV performance pass

## Goal
Make remote-control focus, card/dialog opening and large local-media lists feel immediate and fluid on Android TV, including low-RAM televisions.

## Implemented

- **First Media-card open:** background MediaStore prewarm runs only on TV and stays off the main thread. Normal TVs start after ~650 ms; low-RAM TVs wait ~1.25 s so cold startup/player connection keeps priority.
- **No duplicate scans:** `LocalMediaLibrary.ensureSnapshot()` uses a coroutine `Mutex`; application prewarm and the Media dialog share one in-flight scan. Explicit Refresh still forces a fresh scan.
- **Fast storage switching:** files are grouped by volume once, so choosing internal/USB storage uses a map lookup instead of an O(n) filter across the entire library.
- **Cheaper media cards:** the long catalogue no longer uses Material3 `ListItem`; rows use a fixed 84 dp layout with a shallower composition tree and stable keys/content types.
- **Compose stability:** local-media data models are marked `@Immutable`, and row labels/metadata are cached.
- **Faster top controls:** removed `IntrinsicSize.Min` from Player control cards; bounded widths avoid intrinsic measurement.
- **TV-only input path:** touch gesture recognizers are not installed on the full-screen player or dialog backdrop on television devices. Phone/tablet touch behavior is preserved.
- **Dialog transition work:** the ML Kit available-language collection is cached independently from `activeDialog`, so opening Media/Tracks/Settings/Open Stream does not recreate it.
- **AOT coverage:** baseline profile now includes TV focus, control-card, dialog and local-media hot-path classes.

## Expected effect

The biggest visible improvement should be the Media card after startup/reopen and D-pad scrolling through large libraries. Top-bar card appearance also avoids intrinsic measurement. Exact frame-time/startup gains still need measurement on a real Android TV device because this build environment has no Android SDK/device.

## Recommended device validation

Use a release build with R8 and the Baseline Profile installed. Measure cold start, Media-card open latency, USB/internal-storage switching and a long D-pad scroll with Macrobenchmark/Perfetto on at least one low-end 2–3 GB Android TV and one faster Google TV device.
