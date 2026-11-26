# Privacy model

OxigenPlayer plays local and remote media on the device. Local playback does not
upload media content to OxigenPlayer infrastructure. The media browser requests
read-only access and uses it only to list and play device or removable-storage
media selected by the user.

Network actions are explicit:

- online subtitle search sends the entered title, languages and normal network
  metadata to the selected provider;
- configured TMDB lookup can send the title to improve subtitle matching;
- translation is disabled by default; cloud-capable engines receive subtitle
  text only after the user enables translation and selects a compatible mode;
- locally hosted LibreTranslate or Ollama keeps requests on the configured LAN;
- ML Kit and supported Gemini Nano processing run on-device;
- direct update checks contact the configured HTTPS endpoint.

Provider keys, session tokens and configured bearer tokens are encrypted with AES-GCM under a non-exportable Android Keystore key when available, with memory-only fallback. The OpenSubtitles
password is used only for login and is not stored. The app does not intentionally
log passwords, tokens, provider keys or subtitle text.

Android backup rules exclude credentials, playback history, update downloads and
subtitle working files. Uninstalling removes private application data; files the
user exported through Android's document picker remain at the chosen destination.


Playback control is exposed only to the application and trusted Android system controllers; arbitrary third-party media controllers are rejected by design.
