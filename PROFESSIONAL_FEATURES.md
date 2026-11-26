# Professional Android and Android TV feature set

## TV-first interaction

- D-pad focus visuals and semantic button roles;
- INFO toggles the diagnostics panel;
- MENU opens settings;
- Leanback launcher entry and dedicated wide banner;
- hardware Back behavior follows Android lifecycle without killing the process;
- MediaSession accepts the app itself and trusted system controllers only;
- Picture-in-Picture and background playback use the same playback service.

## Playback engine

- Media3 progressive, HLS, DASH, SmoothStreaming, RTSP/RTSPS and RTMP modules;
- platform-first decoding, with optional GPL software-codec fallback;
- video caps for Auto, 4K UHD, Full HD, HD and Data Saver;
- optional seamless frame-rate matching;
- repeat-one mode and persisted playback speed;
- resume checkpoints persisted every ten seconds and on relevant state changes;
- decoder-friendly seek commit after the user finishes scrubbing.

## Diagnostics

The optional overlay reports the selected video/audio formats, resolution,
frame rate, codec or MIME type, bitrate, channel count, sample rate, buffer,
playback speed, state, active quality cap and sleep timer. It reads selected
tracks through the generic Media3 `Player` API, so it works with the activity's
`MediaController` rather than depending on an in-process `ExoPlayer` instance.

## Network editions

The Play edition disallows cleartext traffic and rejects HTTP media URIs. The
Direct/LAN edition permits user-selected HTTP and local-network sources, but API,
subtitle and update clients remain separated and hardened. Subtitle downloads
require HTTPS, approved provider-domain boundaries and public-only DNS for every
redirect hop.

## Credentials

Provider keys are bring-your-own-key values. They are not compiled into release
artifacts. The app trims and length-limits values before encrypted storage and
never persists the OpenSubtitles password.

## Sleep timer

The timer uses `SystemClock.elapsedRealtime`, avoiding wall-clock changes. Saved
state outside the valid twelve-hour window is discarded, and expiration pauses
playback through the active MediaController.
