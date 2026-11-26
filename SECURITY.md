# Security policy

## Supported builds

Only release artifacts built from the current main branch, signed with the
production certificate and published through the intended channel are supported.
Debug APKs must never be distributed as updates.

## Network boundaries

- `play` has a fail-closed cleartext policy and accepts HTTPS, RTSP and RTSPS media.
- `direct` explicitly enables user-selected HTTP/LAN media.
- API and update clients are HTTPS-only, disable proxies and reject private DNS
  results.
- Subtitle downloads validate HTTPS, port, credentials/fragments, public DNS and
  the provider-specific domain boundary on every redirect hop.
- Update metadata and APK redirects remain restricted to their approved GitHub
  release paths.

No authorization or provider header is forwarded to an unrelated redirect host.

## Secrets

Release BuildConfig fields for provider credentials are always empty. Optional
public-client keys entered by the user are normalized and stored with AES-GCM using a non-exportable Android Keystore key. If encrypted storage cannot be created, secret values are retained
only in memory. The OpenSubtitles password is never persisted.

MediaSession accepts the app controller and trusted system controllers only. This intentionally blocks arbitrary third-party controllers, including unsupported Auto/Wear/assistant clients, from obtaining playback commands.

Developer-local credentials require an explicit debug-only opt-in. Never commit
`oxigenplayer-secrets.properties`, keystores or signing passwords.

## Updates

The direct updater verifies HTTPS destinations, metadata and APK size limits,
SHA-256, ZIP/APK structure, package identity, version code and signing
certificate lineage before opening Android's package installer. The Play variant
has no direct-install permission.

## Supply chain

The Gradle distribution URL and wrapper JAR are pinned by SHA-256, GitHub Actions
are pinned to commit SHAs, repositories are centralized and dependencies use
fixed versions. `scripts/verify-project-policy.py` checks these local invariants.
Dependency locking is enabled. Full Gradle dependency-verification metadata must be generated in a connected,
trusted release environment after resolving the complete dependency graph and
reviewed before publication.

## Reporting a vulnerability

Report vulnerabilities privately to the repository owner with the affected
version, reproduction steps and impact. Do not include credentials, private
subtitle content or user data in a public issue.
