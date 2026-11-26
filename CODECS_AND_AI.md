# Codecs and translation engines

> **Single edition (14 November 2026).** The product flavors were removed. There is now one
> all-inclusive app (direct updates, LAN cleartext playback, FFmpeg software decoders),
> minSdk 24, Gemini Nano removed, built with `:app:assembleDebug` / `:app:assembleRelease`.
> Flavor names and per-flavor task names further down in this file are historical.
> See [VARIANTE_BUILD.md](VARIANTE_BUILD.md).

## Media stack

All variants use AndroidX Media3 1.11.1. The project packages the ExoPlayer
modules for progressive files, DASH, HLS, SmoothStreaming, RTSP, OkHttp-backed
HTTP(S), and RTMP data sources.

The `codecs` flavor dimension has two choices:

- `platform` (default/recommended): Android hardware and platform decoders are
  preferred. This is the smallest and most power-efficient build.
- `full`: keeps Android hardware decoders first and adds NextLib 1.11.1-0.18.0
  as a software fallback. Its FFmpeg renderers cover H.264, HEVC, VP8, VP9 and
  audio including Vorbis, Opus, FLAC, ALAC, MP3, AMR, AAC, AC-3, E-AC-3,
  DTS/DTS-HD and TrueHD.

AV1 playback uses the Android platform decoder when the device provides one.
The official Media3 dav1d AV1 extension is source-built native code rather than
an ordinary Maven dependency, so it is not silently added as an unverifiable
binary. A device without an AV1 platform decoder will still report AV1 as
unsupported.

### License warning for `full`

NextLib is GPLv3. A distributed `full` artifact must meet GPLv3 obligations for
the complete combined application, including corresponding source and legal
notices. See the root `LICENSE`, `licenses/NextLib-NOTICE.txt` and `SOURCE_OFFER_FULL.md`. The
`platform` flavor does not contain NextLib.

## Translation stack

The source and target language are independently selectable in the settings.
Translation remains disabled by default.

The `ai` flavor dimension has two choices:

- `api24` (API 24+): ML Kit Translation 17.0.3, language identification
  17.0.6, Apertium, MyMemory, Mozhi, LibreTranslate, Ollama and optional DeepL.
  (Formerly named `classic`. The unauthenticated Google Translate web endpoint was
  removed in 2.2.3; existing `GOOGLE` selections migrate to `AUTO`.)
- `genai` (API 26+): includes everything in `api24` plus ML Kit GenAI Prompt
  1.0.0-beta4 and an explicit **Gemini Nano** translation engine.
  (Formerly named `nano`.)

Gemini Nano runs through Android AICore and processes subtitle text on-device.
It is shown only in `genai` builds. Device support is still checked at runtime;
unsupported devices fail closed and do not fall back to cloud when Gemini Nano
was selected explicitly. The model may need to download on first use.

Gemini Nano is intentionally not placed in the automatic translation chain:
subtitle translation can generate many requests, while AICore enforces per-app
inference quotas. Users can select it explicitly when quality is more important
than maximum throughput. ML Kit Translation remains the fast local engine for
regular subtitle playback.

## Recommended builds

Small, broadly compatible Play build:

```text
./gradlew bundlePlayPlatformApi24Release
```

Direct APK with the same compatibility profile:

```text
./gradlew assembleDirectPlatformApi24Release
```

Advanced test build with software codecs and Gemini Nano:

```text
./gradlew assembleDirectFullGenaiDebug
```

Before distributing a `full` build, review and satisfy the GPLv3 requirements.
