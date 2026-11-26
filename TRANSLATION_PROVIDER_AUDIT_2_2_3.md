# Translation provider audit — OxigenPlayer 2.2.3

Date: 2026-09-19

## Kept

- **ML Kit 17.0.3** — official Android on-device translation dependency; no per-request cloud API key.
- **Apertium APy** — current public API; free/open-source; keyless.
- **MyMemory** — current public REST API; keyless for basic usage, with usage limits.
- **LibreTranslate** — free/open-source when self-hosted; official hosted service requires an API key.
- **Ollama** — free local/self-hosted translation through a user-selected model.
- **DeepL** — optional BYOK provider; retained only when configured.
- **Gemini Nano / ML Kit Prompt API 1.0.0-beta4** — official current beta integration; retained only in the nano build and gated by runtime AICore availability. Unsupported devices do not expose it as usable.

## Removed

- **Direct Google Translate web endpoint** (`translate.googleapis.com/translate_a/single?client=gtx`) — undocumented/unsupported integration. Old saved selections migrate to AUTO.

## Added

- **Mozhi** — active FOSS translation frontend/API with multiple current public instances and self-hosting support. Default endpoint: `https://mozhi.aryak.me`. The engine can be changed in settings.

## AUTO order

1. ML Kit (when available)
2. Ollama (when configured)
3. LibreTranslate (when configured)
4. Apertium
5. MyMemory
6. Mozhi

This ordering prefers local/on-device paths first and keeps the community-hosted provider as the last network fallback.

## Checked but not added

- **Lingva Translate** — not added because current project reports include 2026 service/translation failures.
- **LibreTranslate official hosted endpoint** — not enabled by default because the official hosted service requires an API key; self-hosted instances remain supported.

Runtime reachability of public community instances can change independently of the app. Mozhi is therefore configurable and used last in AUTO.
