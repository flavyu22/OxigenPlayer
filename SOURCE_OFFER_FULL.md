# Corresponding source process for `full` builds

OxigenPlayer Pro 2.2.7 is distributed under GPL-3.0-only. The `full` flavor also links
NextLib/FFmpeg code under GPLv3. Every distributed `full` APK or AAB must be accompanied
by the complete corresponding source for the exact combined work.

## Pinned NextLib revision

- Maven coordinate: `io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.18.0`
- Upstream tag: `v1.11.1-0.18.0`
- Upstream commit: `d774e7e6d65204d409dc679ab173c67030d4a7d4`
- Exact source archive: `https://github.com/anilbeesetti/nextlib/archive/d774e7e6d65204d409dc679ab173c67030d4a7d4.zip`

## Release procedure

1. Check out the exact signed-release commit.
2. Download or extract the pinned NextLib source revision above into a trusted temporary directory.
3. Run:

   ```text
   python3 scripts/package-gpl-source.py \
     --version 2.2.7 \
     --nextlib-source /trusted/path/nextlib-d774e7e6d65204d409dc679ab173c67030d4a7d4.zip
   ```

4. The script fails if it discovers files matching secret/credential/keystore patterns.
5. Inspect `CORRESPONDING_SOURCE_MANIFEST.json`, the root `LICENSE`, notices, build scripts,
   wrapper files and the expanded `third-party/nextlib-*` tree.
6. Test the generated ZIP on Windows, Linux and macOS and archive its SHA-256 next to the binary.
7. Publish the corresponding-source ZIP with the exact `full` binary and retain it for the period
   required by the chosen GPLv3 distribution method.

This is an engineering compliance process, not legal advice.
