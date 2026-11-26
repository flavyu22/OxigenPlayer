# OxigenPlayer Pro 2.2.9

> **Single edition.** Since 14 November 2026 there is one all-inclusive app (direct updates,
> LAN cleartext playback, FFmpeg software decoders), minSdk 24, without Gemini Nano.
> See [VARIANTE_BUILD.md](VARIANTE_BUILD.md).

OxigenPlayer Pro is an Android and Android TV media player written in Kotlin,
Jetpack Compose and AndroidX Media3. The same project supports touch devices,
D-pad/remote navigation, Picture-in-Picture, background playback through a
MediaSessionService, local/USB media, adaptive streams, external subtitles,
online subtitle providers and optional subtitle translation.

## Requirements

- Android Studio and Android SDK 37
- JDK 25 for the pinned Gradle daemon; application bytecode targets Java 17
- Android 7.0 (API 24) or newer
- The checked-in Gradle wrapper

Run the repository checks before Gradle:

```text
python3 scripts/verify-project-policy.py
python3 -m py_compile scripts/*.py
```

## Build

There is a single edition; the former `play`/`direct`, `platform`/`full` and `api24`/`genai`
flavors were removed.

```text
./gradlew :app:assembleDebug      # all ABIs, for emulators and testing
./gradlew :app:assembleRelease    # R8 + Baseline Profile, ARM only, needs release signing
```

The edition bundles the GPLv3 NextLib/FFmpeg decoders and ships its own updater, so it is
not suitable for Google Play. See [CODECS_AND_AI.md](CODECS_AND_AI.md) before distributing the APK.

For local debugging, `BUILD_DEBUG.bat` builds the single all-inclusive edition
(`:app:assembleDebug`) and copies it to `apk-debug/OxigenPlayer-debug.apk`. It allows
sideloading with direct updates, HTTP playback only towards the local network, the
FFmpeg software-decoder recovery path and the standard translation stack (API 24+).
[VARIANTE_BUILD.md](VARIANTE_BUILD.md) explains the build in Romanian.

## Professional playback features

Version 2.2.9 includes:

- Auto, 4K UHD, Full HD, HD and Data Saver video-selection profiles;
- seamless content frame-rate matching on compatible televisions;
- repeat-current-media mode;
- a monotonic sleep timer with 15, 30, 60 and 120-minute presets;
- a localized technical overlay with selected codec, resolution, frame rate,
  bitrate, channels, sample rate, buffer, speed and playback state;
- INFO-key diagnostics and MENU-key settings shortcuts on Android TV;
- command-aware MediaController operations, so unavailable remote-session
  commands fail closed instead of throwing;
- a dedicated Android TV launcher banner and semantic D-pad controls;
- skip-silence playback, Fit/Fill/Stretch aspect-ratio modes and an A-B loop repeat;
- an "Open stream" dialog for HLS/DASH/RTSP(RTSPS) and direct HTTPS/LAN media URLs, validated
  with the same playback URI policy as session intents;
- a custom `oxigenplayer://` deep link so Jellyfin, Stremio, Infuse and similar apps can offer
  their current stream in OxigenPlayer as an external player;
- secure, user-supplied provider keys stored with AES-GCM under a non-exportable Android Keystore key;
- strict HTTPS/domain validation for subtitle redirects;
- cleartext HTTP allowed only towards the local network (NAS, home server).

The custom timeline supports touch, mouse and TV remotes, including tap/drag
seeking, elapsed/total/remaining time, buffered progress, Home/End navigation and
predictable repeated D-pad seeking in 10-second steps. Only the final seek is committed to the
decoder after each remote-control burst or touch scrub.

## Low-RAM Android TV mode

OxigenPlayer detects constrained devices once per process using Android's low-RAM signal plus a conservative RAM threshold. On low-RAM televisions it automatically:

- removes focus scaling, z-index promotion and focus color animations from TV cards;
- disables player control entrance/exit animations and timeline/subtitle padding animations;
- samples playback position at 500 ms and buffered position at 1 s while controls are visible;
- suspends invisible position polling while a dialog is open;
- uses an opaque dialog background and hides player controls behind dialogs;
- reduces ExoPlayer buffering further on very-low-RAM devices while preserving adaptive playback;
- shrinks translation/subtitle LRU caches, prefetch queues and concurrent translation work;
- creates subtitle-search HTTP clients only when the subtitle-search screen is actually opened;
- reuses lazy-list content types and cached labels in the media browser;
- sorts large MediaStore libraries without repeated `lowercase()` allocations or per-volume result lists.

The normal profile keeps the richer animations and larger caches on capable devices.

## Provider configuration

Open **Settings → API keys and accounts** to enter public-client keys for
OpenSubtitles, Subs.ro, SubDL and TMDB. Translation supports offline ML Kit, keyless Apertium/MyMemory, configurable Mozhi, self-hosted LibreTranslate/Ollama and optional DeepL. The previous undocumented direct Google web endpoint was removed. DeepL can be configured in translation
settings. Values are normalized, length-limited and stored with AES-GCM under a non-exportable Android Keystore key
when secure storage is available; otherwise they remain in
memory for the current process.

Developer-local keys can still be embedded only into explicit debug builds by
placing them in `~/.gradle/oxigenplayer-secrets.properties` and setting
`OXIGEN_EMBED_PROVIDER_CREDENTIALS=true`. Release builds always receive empty
BuildConfig values. Never place privileged server secrets in an APK.

## Verification

CI performs repository policy checks, Python validation, unit tests, lint and
assembly for all eight debug combinations, minified/signing verification for all
eight release combinations, and connected instrumentation tests on API 37 phone and
Android TV emulator images, including a Direct/Full/Genai TV variant. A real-device release checklist remains mandatory
for decoder, HDMI, display-mode, remote-control, USB/NAS and installer behavior.

See [SECURITY.md](SECURITY.md), [PRIVACY.md](PRIVACY.md), [RELEASE.md](RELEASE.md)
and [PROFESSIONAL_FEATURES.md](PROFESSIONAL_FEATURES.md).

## License

OxigenPlayer Pro 2.2 source is provided under GPL-3.0-only. The complete license
is available in the root `LICENSE` file and is packaged in the application as
`res/raw/gpl_3_0.txt`. `full` variants additionally include the pinned NextLib
notice and must be distributed with the complete corresponding-source archive
described in `SOURCE_OFFER_FULL.md`.
